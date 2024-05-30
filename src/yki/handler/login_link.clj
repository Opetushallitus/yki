(ns yki.handler.login-link
  (:require [buddy.core.codecs :refer [bytes->hex]]
            [buddy.core.hash :as hash]
            [clj-time.core :as t]
            [clojure.tools.logging :as log]
            [compojure.api.sweet :refer [api context POST]]
            [integrant.core :as ig]
            [pgqueue.core :as pgq]
            [ring.util.http-response :refer [forbidden ok]]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.localisation :as localisation]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.handler.routing :as routing]
            [yki.job.job-queue]
            [yki.spec :as ys]
            [yki.util.common :as c]
            [yki.util.template-util :as template-util]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defn create-and-send-link [db url-helper email-q lang login-link exam-session]
  (let [code          (str (random-uuid))
        login-url     (url-helper :yki.login-link.url code)
        email         (:email (registration-db/get-participant-by-id db (:participant_id login-link)))
        link-type     (:type login-link)
        hashed        (sha256-hash code)
        template-data (assoc exam-session :subject (str (localisation/get-translation lang "email.login.subject"))
                                          :language (template-util/get-language (:language_code exam-session) lang)
                                          :level (template-util/get-level (:level_code exam-session) lang)
                                          :login_url login-url)]
    (login-link-db/create-login-link! db (assoc login-link :code hashed))
    (log/info "Login link created for" email ". Adding to email queue")
    (pgq/put email-q
             {:recipients [email]
              :created    (System/currentTimeMillis)
              :subject    (template-util/login-subject template-data)
              :body       (template-util/render link-type lang template-data)})))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db email-q url-helper access-log]}]
  {:pre [(some? db) (some? email-q) (some? url-helper) (some? access-log)]}
  (api
    (context routing/login-link-api-root []
      :coercion :spec
      :middleware [access-log]
      ; Handler only called when ordering registration link
      ; to email, as an alternative to Suomi.fi-authentication.
      (POST "/" _
        :body [login-link ::ys/login-link]
        :query-params [lang :- ::ys/language-code]
        :return ::ys/response
        (let [exam-session-id (:exam_session_id login-link)
              exam-session    (exam-session-db/get-exam-session-with-location db exam-session-id lang)]
          (if (:open exam-session)
            (let [participant-id           (:id (registration-db/get-or-create-participant! db {:external_user_id (:email login-link)
                                                                                                :email            (:email login-link)}))
                  registration-url         (url-helper :yki-ui.exam-session-registration.url exam-session-id)
                  registration-expired-url (url-helper :yki-ui.exam-session-registration-expired.url exam-session-id)
                  link                     (assoc login-link :participant_id participant-id
                                                             :type "LOGIN"
                                                             :expires_at (c/date-from-now 1)
                                                             :success_redirect registration-url
                                                             :expired_link_redirect registration-expired-url
                                                             :registration_id nil)]
              (log/info "Requested login link:" login-link)
              (if
                (login-link-db/get-recent-login-link-by-exam-session-and-participant
                  db
                  exam-session-id
                  participant-id
                  (t/minus (t/now) (t/minutes 5)))
                (do (log/info
                      "Found recent login-link for email and exam session. Not sending another email yet to avoid flooding the email service. Email:"
                      (:email login-link)
                      ", exam-session-id:"
                      exam-session-id)
                    (ok {:success true}))
                (when (create-and-send-link db url-helper email-q lang link exam-session)
                  (ok {:success true}))))
            (do (log/error "Requested login link, but registration for exam session isn't open." login-link)
                (forbidden))))))))
