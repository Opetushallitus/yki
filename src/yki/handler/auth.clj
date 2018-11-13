(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok]]
            [yki.auth.cas-auth :as cas-auth]
            [yki.auth.code-auth :as code-auth]
            [yki.auth.header-auth :as header-auth]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client onr-client permissions-client access-log db]}]
  (api
   (context routing/auth-root []
     :middleware [auth access-log]
     (GET "/initsession" [lang :as request]
       (header-auth/login request onr-client url-helper))
     (GET "/user" {session :session}
       (ok {:session session}))
     (GET "/login" [code lang]
       (code-auth/login db code lang url-helper))
     (context routing/virkailija-auth-uri []
       (POST "/" [logoutRequest :as request])

       (GET "/callback" [ticket :as request]
         (cas-auth/login ticket request cas-client permissions-client url-helper db))
       (GET "/logout" {session :session}
         (cas-auth/logout session url-helper))
       (GET "/user" {session :session}
         (ok {:session (update-in session [:identity] dissoc :ticket)}))))))
