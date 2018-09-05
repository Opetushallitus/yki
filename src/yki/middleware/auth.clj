(ns yki.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [buddy.auth.accessrules :refer [wrap-access-rules success error]]
            [buddy.auth.backends.session :refer [session-backend]]
            [integrant.core :as ig]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [response status]]
            [ring.util.http-response :refer [unauthorized]]))

(defn authenticated
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (unauthorized {:error "Not authorized"}))))

(def backend (session-backend))

(defn any-access [request]
  true)

(defn logged-in? [request]
  ; let [username (cas/username-from-valid-service-ticket cas-config login-callback ticket)]
  ; (let [ticket (-> request :session :identity :ticket)]
  ;   (cas-store/logged-in? ticket)))
  false)

(defn- authenticated-access [request]
  (if (logged-in? request)
    true
    (error "Authentication required")))

(defn access-error [req val]
  (unauthorized val))

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper]}]

  (defn- redirect-to-cas-login [request _]
    {:status  302
     :headers {"Location" (url-helper :cas.login)
               "Content-Type" "text/plain"}
     :session {:original-url (request-url request)}
     :body    "Access to is not authorized, redirecting to login"})

  (def ^:private rules [{:pattern #".*/auth/cas/callback"
                       :handler any-access}
                      {:pattern #".*/api/.*"
                       :handler authenticated-access
                       :on-error access-error}
                      {:pattern #".*/auth/cas"
                       :handler authenticated-access
                       :on-error redirect-to-cas-login}
                      {:pattern #".*"
                       :handler authenticated-access
                       :on-error redirect-to-cas-login}])

  (defn with-authentication [handler]
    (-> handler
        (wrap-authentication backend)
        (wrap-access-rules {:rules rules})))
  )

