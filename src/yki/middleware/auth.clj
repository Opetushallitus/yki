(ns yki.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [ring.middleware.session :refer [wrap-session]]
            [buddy.auth.accessrules :refer [wrap-access-rules success error]]
            [buddy.auth.backends.session :refer [session-backend]]
            [integrant.core :as ig]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [response status]]
            [ring.util.http-response :refer [unauthorized]]))

; (defn authenticated
;   [handler]
;   (fn [request]
;     (if (authenticated? request)
;       (handler request)
;       (unauthorized {:error "Not authorized"}))))

(def backend (session-backend))

(defn any-access [request]
  true)

(defn logged-in? [request]
  (if-let [ticket (-> request :session :identity :user :ticket)]
    true
    false))

(defn- authenticated-access [request]
  (if (logged-in? request)
    true
    (error "Authentication required")))

(defn access-error [req val]
  (unauthorized val))

  ; (defn- redirect-to-cas-login [request _]
  ;   {:status  302
  ;    :headers {"Location" (url-helper :cas.login)
  ;              "Content-Type" "text/plain"}
  ;    :session {:original-url (request-url request)}
  ;    :body    "Access to is not authorized, redirecting to login"})

  ; (def ^:private rules [{:pattern #".*/auth/cas/callback"
  ;                        :handler any-access}
  ;                       {:pattern #".*/api/.*"
  ;                        :handler authenticated-access
  ;                        :on-error access-error}
  ;                       {:pattern #".*/auth/cas"
  ;                        :handler authenticated-access
  ;                        :redirect (url-helper :cas.login)}
  ;                     ;  :on-error redirect-to-cas-login}
  ;                       {:pattern #".*"
  ;                        :handler authenticated-access
  ;                        :on-error redirect-to-cas-login}])

(defn- rules [redirect-url] [{:pattern #".*/auth/cas/callback"
                              :handler any-access}
                             {:pattern #".*/api/.*"
                              :handler authenticated-access
                              :on-error access-error}
                             {:pattern #".*/auth/cas"
                              :handler authenticated-access
                              :redirect redirect-url}
                              ;  :on-error redirect-to-cas-login}
                             {:pattern #".*"
                              :handler authenticated-access
                              :redirect redirect-url}])

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper]}]

  (defn with-authentication [handler]
    (-> handler
        (wrap-authentication backend)
        (wrap-access-rules {:rules (rules (url-helper :cas.login))})
        (wrap-session))))

