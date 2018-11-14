(ns yki.handler.login-link
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
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

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db email-q url-helper]}]
  (api
   (context routing/login-link-api-root []
     :coercion :spec
     (POST "/" request
       :body [login-link ::ys/login-link]
       :query-params [lang :- ::ys/language_code]
       :return ::ys/response
       (registration-db/create-participant-if-not-exists! db (:email login-link))
       (let [code (str (UUID/randomUUID))
             login-url (str (url-helper :host-yki-oppija) "?code=" code)
             expires-at (t/plus (t/now) (t/days 1))
             hashed (sha256-hash code)]
         (when (login-link-db/create-login-link!
                db
                (assoc login-link
                       :code hashed
                       :type "REGISTRATION"
                       :expires_at expires-at
                       :registration_id nil))
           (pgq/put
            email-q
            {:recipients [(:email login-link)],
             :subject (template-util/subject "login_link" lang),
             :body
             (template-util/render "login_link" lang {:login-url login-url})})
           (ok {:success true})))))))
