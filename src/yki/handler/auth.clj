(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok]]
            [yki.auth.cas-auth :as cas-auth]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client permissions-client access-log]}]
  (api
   (context routing/auth-root []
     :middleware [auth access-log]
     (GET "/initsession" [lang :as request]
        (ok {:headers (:headers request)}))
     (context routing/virkailija-auth-uri []
       (GET "/callback" [ticket :as request]
         (cas-auth/login ticket request cas-client permissions-client url-helper))
       (GET "/logout" {session :session}
         (cas-auth/logout session url-helper))
       (GET "/user" {session :session}
         (ok {:session (update-in session [:identity] dissoc :ticket)}))))))
