(ns yki.auth.cas-auth
  (:require [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.auth.backends.session :refer [session-backend]]
            [ring.util.request :refer [request-url]]
            [integrant.core :as ig]))

; (def cas-auth-backend (session-backend))

; (defn- cas-authfn
;   [req {:keys [username password]}]
;   ("username"))

; (defn- auth
;   [handler]
;   (wrap-authentication handler cas-auth-backend))

; (defn- send-not-authenticated-api-response [& _]
;   {:status  401
;    :headers {"Content-Type" "application/json"}
;    :body    {:error "Not authenticated"}})

; (defn- redirect-to-login [request _]
;   {:status  302
;    :headers {"Location" "(auth-utils/cas-auth-url)"
;              "Content-Type" "text/plain"}
;    :session {:original-url (request-url request)}
;    :body    (str "Access to " (:uri request) " is not authorized, redirecting to login")})

; (defmethod ig/init-key :yki.auth.cas-auth/auth [_ options]
;   auth)
