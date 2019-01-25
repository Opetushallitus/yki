(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
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
     (GET "/" [examSessionId :as request]
       (found (url-helper :exam-session.redirect examSessionId)))
     (GET "/initsession" [lang :as request]
       (header-auth/login request onr-client url-helper))
     (GET "/user" {session :session}
       (ok (update-in session [:identity] dissoc :ticket)))
     (GET "/login" [code lang]
       (code-auth/login db code lang url-helper))
     (context routing/virkailija-auth-uri []
       (POST "/" request
         (cas-auth/cas-logout db (slurp (:body request))))
       (GET "/callback" [ticket :as request]
         (cas-auth/login ticket request cas-client permissions-client onr-client url-helper db))
       (GET "/logout" {session :session}
         (cas-auth/logout session url-helper))))))
