(ns yki.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [buddy.auth.accessrules :refer [wrap-access-rules success error]]
            [buddy.auth.backends.session :refer [session-backend]]
            [integrant.core :as ig]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [response status]]
            [ring.util.http-response :refer [unauthorized]]))

(def backend (session-backend))

(defn any-access [request]
  true)

(defn- authenticated-access [request]
  (if (-> request :session :identity :ticket)
    true
    (error "Authentication required")))

(defn access-error [req val]
  (unauthorized val))

(defn- rules [redirect-url] [{:pattern #".*/auth/cas/callback"
                              :handler any-access}
                             {:pattern #".*/api/.*"
                              :handler authenticated-access
                              :on-error access-error}
                             {:pattern #".*/auth/cas"
                              :handler authenticated-access
                              :redirect redirect-url}
                             {:pattern #".*"
                              :handler authenticated-access
                              :redirect redirect-url}])

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper session-config]}]
  (defn with-authentication [handler]
    (-> handler
        (wrap-access-rules {:rules (rules (url-helper :cas.login))})
        (wrap-authentication backend)
        (wrap-authorization backend)
        (wrap-session {:store (cookie-store {:key (:key session-config)})
                       :cookie-name "yki"
                       :cookie-attrs (:cookie-attrs session-config)}))))

