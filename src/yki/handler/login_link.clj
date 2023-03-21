(ns yki.handler.login-link
  (:require [buddy.core.codecs :refer [bytes->hex]]
            [buddy.core.hash :as hash]
            [clojure.tools.logging :as log]
            [compojure.api.sweet :refer [api context POST]]
            [integrant.core :as ig]
            [ring.util.http-response :refer [ok]]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.handler.routing :as routing]
            [yki.job.job-queue]
            [yki.registration.registration :as registration]
            [yki.spec :as ys]
            [yki.util.common :as c]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db email-q url-helper access-log]}]
  {:pre [(some? db) (some? email-q) (some? url-helper) (some? access-log)]}
  (api
   (context routing/login-link-api-root []
     :coercion :spec
     :middleware [access-log]
     ; Handler only called when ordering registration link
     ; to email, as an alternative to Suomi.fi-authentication.
     (POST "/" request
       :body [login-link ::ys/login-link]
       :query-params [lang :- ::ys/language-code
                      use-yki-ui :- ::ys/use-yki-ui]
       :return ::ys/response
       (log/info "Login link requested for: " login-link)
       (let [participant-id           (:id (registration-db/get-or-create-participant! db {:external_user_id (:email login-link)
                                                                                           :email            (:email login-link)}))
             exam-session-id          (:exam_session_id login-link)
             registration-url         (if use-yki-ui
                                        (url-helper :yki-ui.exam-session-registration.url exam-session-id)
                                        (url-helper :exam-session.redirect exam-session-id lang))
             registration-expired-url (if use-yki-ui
                                        (url-helper :yki-ui.exam-session-registration-expired.url exam-session-id)
                                        (url-helper :link-expired.redirect lang))
             link                     (assoc login-link :participant_id participant-id
                                                        :type "LOGIN"
                                                        :expires_at (c/date-from-now 1)
                                                        :success_redirect registration-url
                                                        :expired_link_redirect registration-expired-url
                                                        :registration_id nil)
             exam-session             (exam-session-db/get-exam-session-with-location db exam-session-id lang)]
         (when (registration/create-and-send-link db url-helper email-q lang link exam-session)
           (ok {:success true})))))))
