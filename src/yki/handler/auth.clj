(ns yki.handler.auth
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok found]]
    [yki.auth.cas-auth :as cas-auth]
    [yki.auth.code-auth :as code-auth]
    [yki.handler.routing :as routing]
    [yki.middleware.access-log]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [auth url-helper cas-client onr-client permissions-client access-log db]}]
  {:pre [(some? auth) (some? url-helper) (some? cas-client) (some? onr-client) (some? permissions-client) (some? access-log) (some? db)]}
  (api
    (context routing/auth-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/user" {session :session}
        (ok (update-in session [:identity] dissoc :ticket)))
      (GET "/login" [code lang]
        (code-auth/login db code lang url-helper))
      (GET "/logout" {session :session}
        :query-params [{redirect :- ::ys/redirect-to nil}]
        (let [lang (or (get-in session [:identity :lang]) "fi")]
          (if (= "SUOMIFI" (:auth-method session))
            (if redirect
              (cas-auth/oppija-logout (url-helper :cas-oppija.logout.redirect-to-url redirect))
              (cas-auth/oppija-logout (url-helper :cas-oppija.logout lang)))
            (code-auth/logout
              (or redirect
                  (url-helper :yki.default.logout.redirect lang))))))
      (GET "/logout/cas/callback" _
        :query-params [{redirect :- ::ys/redirect-to nil}]
        (-> (found redirect)
            (assoc :session nil)))
      (POST "/callback" request
        (cas-auth/cas-oppija-logout url-helper))
      (GET "/callback*" [ticket :as request]
        (cas-auth/oppija-login ticket request cas-client onr-client url-helper))
      (context routing/virkailija-auth-uri []
        (POST "/callback" request
          (cas-auth/cas-logout db (get-in request [:params :logoutRequest])))
        (GET "/" {session :session}
          (found (cas-auth/create-redirect-uri-from-session session url-helper)))
        (GET "/callback" [ticket :as request]
          (cas-auth/login ticket request cas-client permissions-client onr-client url-helper db))
        (GET "/logout" {session :session}
          (cas-auth/logout session url-helper))))))
