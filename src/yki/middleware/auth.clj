(ns yki.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [buddy.auth.accessrules :refer [wrap-access-rules success error]]
            [buddy.auth.backends.session :refer [session-backend]]
            [integrant.core :as ig]
            [clout.core :as clout]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [response status]]
            [ring.util.http-response :refer [unauthorized forbidden]]))

(def backend (session-backend))

(def oph-oid "1.2.246.562.10.00000000001")

(def organizer-routes
  ["*/organizer/:oid"
   "*/organizer/:oid/*"])

(defn- any-access [request]
  true)

(defn- authenticated [request]
  (if (-> request :session :identity :ticket)
    true
    (error unauthorized)))

(defn- has-oid? [permission oid]
  (= (permission "oid") oid))

(defn- match-oid
  [request routes]
  (:oid (into {} (map #(clout/route-matches % {:path-info (:uri request)}) routes))))

(defn get-organizations-from-session [session]
  (get-in session [:identity :organizations]))

(defn- allowed-organization? [session oid]
  (some #(= (:oid %) oid) (get-organizations-from-session session)))

(defn oph-user?
  [session]
  (if (allowed-organization? session oph-oid)
    true))

(defn oph-user-access
  [request]
  (if (oph-user? (:session request))
    true
    (error forbidden)))

(defn- permission-to-organization
  [request]
  (if-let [oid (match-oid request organizer-routes)]
    (allowed-organization? (:session request) oid)
    true))

(defn- rules [redirect-url] [{:pattern #".*/auth/cas/callback"
                              :handler any-access}
                             {:pattern #".*/api/virkailija/organizer/.*/exam-session.*"
                              :handler {:and [authenticated {:or [oph-user-access permission-to-organization]}]}}
                             {:pattern #".*/api/virkailija/organizer"
                              :handler {:and [authenticated oph-user-access]}
                              :request-method :post}
                             {:pattern #".*/api/virkailija/organizer/.*"
                              :handler {:and [authenticated oph-user-access]}
                              :request-method :put}
                             {:pattern #".*/api/virkailija/organizer/.*"
                              :handler {:and [authenticated oph-user-access]}
                              :request-method :delete}
                             {:pattern #".*/api/virkailija/organizer"
                              :handler authenticated
                              :request-method :get}
                             {:pattern #".*/api/virkailija/organizer/.*"
                              :handler {:and [authenticated {:or [oph-user-access permission-to-organization]}]}
                              :request-method :get}
                             {:pattern #".*/auth/cas"
                              :handler authenticated
                              :redirect redirect-url}
                             {:pattern #".*"
                              :handler authenticated
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

