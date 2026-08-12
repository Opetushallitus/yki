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
            [yki.middleware.error-boundary :refer [with-error-boundary]]
            [yki.spec :as ys]
            [yki.util.common :as c]
            [yki.util.template-util :as template-util]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defn create-and-send-link [db url-helper email-q lang login-link exam-session to-queue?]
  (let [code              (str (random-uuid))
        login-url         (url-helper :yki.login-link.url code)
        email             (:email (registration-db/get-participant-by-id db (:participant_id login-link)))
        link-type         (if to-queue? "LOGIN_QUEUE" (:type login-link))
        subject           (str (localisation/get-translation lang (if to-queue? "email.login_queue.subject" "email.login.subject")))
        hashed            (sha256-hash code)
        partial-exam-type (registration-db/get-partial-exam-type-for-login db (:registration_id login-link) (:exam_session_id login-link))
        template-data     (assoc exam-session :subject subject
                                 :language (template-util/get-language (:language_code exam-session) lang)
                                 :level (template-util/get-level (:level_code exam-session) lang)
                                 :login_url login-url
                                 :subtests (template-util/get-registration-subtests (:exam_session_type exam-session) partial-exam-type lang))]
    (login-link-db/create-login-link! db (assoc login-link :code hashed))
    (log/info "Login link created for" email ". Adding to email queue")
    (pgq/put email-q
             {:recipients [email]
              :created    (System/currentTimeMillis)
              :subject    (template-util/login-subject template-data)
              :body       (template-util/render link-type lang template-data)})))

(defn send-renewed-user-portal-link [db url-helper email-q lang login-link code]
  (let [login-url     (url-helper :yki.login-link.url code)
        email         (:email (registration-db/get-participant-by-id db (:participant_id login-link)))
        link-type     "LOGIN_RENEW"
        subject       (str (localisation/get-translation lang "email.login_renew.subject"))
        template-data {:subject subject
                       :login_url login-url}]
    (log/info "Login link renewed for" email ". Adding to email queue")
    (pgq/put email-q
             {:recipients [email]
              :created    (System/currentTimeMillis)
              :subject    subject
              :body       (template-util/render link-type lang template-data)})))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db auth email-q url-helper access-log]}]
  {:pre [(some? db) (some? auth) (some? email-q) (some? url-helper) (some? access-log)]}
  (api
    (context routing/login-link-api-root []
      :coercion :spec
      :middleware [auth access-log with-error-boundary]
      ; Handler only called when ordering registration link
      ; to email, as an alternative to Suomi.fi-authentication.
      (POST "/" {session :session}
        :body [login-link ::ys/login-link]
        :query-params [lang :- ::ys/language-code]
        :return ::ys/response
        (let [exam-session-id (:exam_session_id login-link)
              exam-session    (exam-session-db/get-exam-session-with-location db exam-session-id lang)]
          (if (:open exam-session)
            (let [participant-id           (:id (registration-db/get-or-create-participant! db {:external_user_id (:email login-link)
                                                                                                :email            (:email login-link)}))
                  registration-kind        (or (:registration_kind login-link) "ADMISSION")
                  registration-id          (:registration_id login-link)
                  registration-matches     (registration-db/check-registration-id-matches-session db registration-id participant-id (:yki-session-id session))
                  to-queue?                (= "QUEUE" registration-kind)
                  registration-url         (cond
                                              (and to-queue? registration-id) (url-helper :yki-ui.exam-session-queue.url exam-session-id registration-id)
                                              registration-id                 (url-helper :yki-ui.exam-session-registration.url exam-session-id registration-id)
                                              :else                           (url-helper :exam-session.url exam-session-id))
                  registration-expired-url (url-helper :yki-ui.exam-session-registration-expired.url exam-session-id)
                  link                     (assoc login-link :participant_id participant-id
                                                  :type "LOGIN"
                                                             ; Login link should be valid for the current day PLUS ONE FULL DAY.
                                                  :expires_at (c/date-from-now 2)
                                                  :success_redirect registration-url
                                                  :expired_link_redirect registration-expired-url
                                                  :registration_id registration-id
                                                  :user_data {:previous-session-id (:yki-session-id session)})]
              (log/info "Requested login link:" login-link)
              (if (and registration-id (nil? registration-matches))
                (do (log/error "Requested login link, but participant doesn't match registration")
                    (forbidden))
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
                  (when (create-and-send-link db url-helper email-q lang link exam-session to-queue?)
                                        ; If user isn't properly logged in, ie. auth-method is "SESSION", clear session details after ordering login link.
                                        ; This is done to allow users to order multiple login links to one exam session.
                                        ; The use case is mostly related to testing in DEV/QA environments, but can also be a legitimate scenario in production use.
                    (let [auth-method   (:auth-method session)
                          session-auth? (= "SESSION" auth-method)]
                      (cond->
                        (ok {:success true})
                        session-auth?
                        (assoc :session nil)))))))
            (do (log/error "Requested login link, but registration for exam session isn't open." login-link)
                (forbidden)))))
      (POST "/renew" {session :session}
        :body-params [code :- ::ys/login-code
                      lang :- ::ys/language-code]
        :return ::ys/response
        (let [hashed (sha256-hash code)
              new-code (str (random-uuid))
              new-hashed (sha256-hash new-code)
              expires-at (c/date-from-now (inc 14))]
          (if-let [new-login-link (login-link-db/renew-user-portal-link! db hashed new-hashed expires-at)]
            (do
              (send-renewed-user-portal-link db url-helper email-q lang new-login-link new-code)
              (ok {:success true}))
            (ok {:success false :error "No login link found"})))))))
