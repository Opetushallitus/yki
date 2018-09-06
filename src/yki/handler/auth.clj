(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas-access :as cas]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [db auth url-helper cas-access]}]
  (api
   (context routing/virkailija-auth-root []
     :middleware [auth]
     (GET "/callback" [ticket :as request]
       (let [client (cas-access url-helper)
             username (.validate-ticket client ticket)
             session (:session request)]
         (do
            ; store ticket to db
           (info "user" username "logged in")
           (-> (redirect "/yki/auth/cas/session")
               (assoc :session {:identity {:user {:username username
                                                  :ticket ticket}}})))))
     (GET "/session" {session :session}
       (response {:session session})))))
