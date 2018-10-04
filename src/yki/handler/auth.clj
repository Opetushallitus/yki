(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log :as access-log]
            [ring.util.response :refer [response]]
            [yki.auth.cas-auth :as cas-auth]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client permissions-client]}]
  (api
   (context routing/virkailija-auth-root []
     :middleware [auth access-log/with-logging]
     (GET "/callback" [ticket :as request]
       (cas-auth/login ticket request cas-client permissions-client url-helper))
     (GET "/logout" {session :session}
       (cas-auth/logout session url-helper))
     (GET "/user" {session :session}
       (response {:session (update-in session [:identity] dissoc :ticket)})))))
