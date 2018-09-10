(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [yki.auth.cas-auth :as cas-auth]
            [yki.boundary.permissions :as permissions]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client permissions-client]}]
  (api
   (context routing/virkailija-auth-root []
     :middleware [auth]
     (GET "/callback" [ticket :as request]
       (cas-auth/login ticket request cas-client permissions-client))
     (GET "/logout" {session :session}
       (cas-auth/logout session url-helper))
     (GET "/user" {session :session}
       (response {:session (update-in session [:identity] dissoc :ticket)})))))
