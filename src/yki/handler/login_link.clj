(ns yki.handler.login-link
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.spec :as ys]
            [ring.util.http-response :refer [ok]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [integrant.core :as ig])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (-> (hash/sha256 code)
      (bytes->hex)))

(defmethod ig/init-key :yki.handler/login-link [_ {:keys [db]}]
  (api
   (context routing/login-link-api-root []
     :coercion :spec
     (POST "/" request
       :body [login-link ::ys/login-link]
       :return ::ys/response
       (registration-db/create-participant-if-not-exists! db (:email login-link))
       (let [code (str (UUID/randomUUID))
             hashed (sha256-hash code)]
         (if (login-link-db/create-login-link! db (assoc login-link :code hashed :type "REGISTRATION" :registration_id nil))
           (ok {:success true})))))))
