(ns yki.handler.login-link
  (:require [compojure.api.sweet :refer [api context POST]]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.registration.registration :as registration]
            [yki.spec :as ys]
            [yki.util.common :as c]
            [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [yki.job.job-queue]
            [ring.util.http-response :refer [ok]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer [bytes->hex]]
            [integrant.core :as ig])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db email-q url-helper access-log]}]
  {:pre [(some? db) (some? email-q) (some? url-helper) (some? access-log)]}
  (api
   (context routing/login-link-api-root []
     :coercion :spec
     :middleware [access-log]
     (POST "/" request
       :body [login-link ::ys/login-link]
       :query-params [lang :- ::ys/language-code]
       :return ::ys/response
       (let [participant-id   (:id (registration-db/get-or-create-participant! db {:external_user_id (:email login-link)
                                                                                   :email (:email login-link)}))
             exam-session-id  (:exam_session_id login-link)
             exam-session     (exam-session-db/get-exam-session-with-location db exam-session-id lang)]
         (when (registration/create-and-send-link db url-helper email-q lang
                                                  (assoc login-link
                                                         :participant_id participant-id
                                                         :type "LOGIN"
                                                         :expires_at (c/date-from-now 1)
                                                         :expired_link_redirect (url-helper :link-expired.redirect lang)
                                                         :success_redirect (url-helper :exam-session.redirect exam-session-id lang)
                                                         :registration_id nil) exam-session)
           (ok {:success true})))))))
