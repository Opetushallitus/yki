(ns yki.handler.login-link
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.registration.registration :as registration]
            [yki.spec :as ys]
            [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [yki.util.template-util :as template-util]
            [yki.job.job-queue]
            [ring.util.http-response :refer [ok]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [integrant.core :as ig])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db email-q url-helper access-log]}]
  {:pre [(some? db) (some? access-log) (some? url-helper) (some? email-q)]}
  (api
   (context routing/login-link-api-root []
     :coercion :spec
     :middleware [access-log]
     (POST "/" request
       :body [login-link ::ys/login-link]
       :query-params [lang :- ::ys/language_code]
       :return ::ys/response
       (let [participant-id (:id (registration-db/get-or-create-participant! db {:external_user_id (:email login-link)
                                                                                 :email (:email login-link)}))]
         (when (registration/create-and-send-link db url-helper email-q lang
                                                  (assoc login-link
                                                         :participant_id participant-id
                                                         :type "LOGIN_LINK"
                                                         :expired_link_redirect (url-helper :link-expired.redirect)
                                                         :success_redirect (url-helper :login-link.redirect (:exam_session_id login-link))
                                                         :registration_id nil) {} 1)
           (ok {:success true})))))))
