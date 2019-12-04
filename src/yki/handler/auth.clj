(ns yki.handler.auth
  (:require [compojure.api.sweet :refer [api context GET POST]]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log]
            [yki.spec :as ys]
            [ring.util.http-response :refer [ok found]]
            [yki.auth.cas-auth :as cas-auth]
            [yki.auth.code-auth :as code-auth]
            [yki.auth.header-auth :as header-auth]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client onr-client permissions-client access-log db]}]
  {:pre [(some? auth) (some? url-helper) (some? cas-client) (some? onr-client) (some? permissions-client) (some? access-log) (some? db)]}
  (api
   (context routing/auth-root []
     :no-doc true
     :middleware [auth access-log]
     (GET "/initsession" [lang :as request]
       (header-auth/login request onr-client url-helper))
     (GET "/user" {session :session}
       (ok (update-in session [:identity] dissoc :ticket)))
     (GET "/login" [code lang]
       (code-auth/login db code lang url-helper))
      (GET "/logout" {session :session}
        (if (= "SUOMIFI" (:auth-method session))
          (header-auth/logout url-helper (or (get-in session [:identity :lang]) "fi"))
          (code-auth/logout url-helper (or (get-in session [:identity :lang]) "fi"))))
     (context routing/virkailija-auth-uri []
       (POST "/callback" request
         (cas-auth/cas-logout db (get-in request [:params :logoutRequest])))
       (GET "/" {session :session}
         (found (cas-auth/create-redirect-uri-from-session session url-helper)))
       (GET "/callback" [ticket :as request]
         (cas-auth/login ticket request cas-client permissions-client onr-client url-helper db))
       (GET "/logout" {session :session}
         (cas-auth/logout session url-helper))))))
