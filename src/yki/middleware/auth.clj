(ns yki.middleware.auth
  (:require
   [yki.handler.routing :as routing]
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [buddy.auth.accessrules :refer [wrap-access-rules success error]]
   [buddy.auth.backends.session :refer [session-backend]]
   [integrant.core :as ig]
   [clout.core :as clout]
   [ring.util.request :refer [request-url]]
   [ring.util.http-response :refer [unauthorized forbidden found see-other]]))

(def backend (session-backend))

(def oph-oid "1.2.246.562.10.00000000001")

(def organizer-routes
  ["*/organizer/:oid"
   "*/organizer/:oid/*"])

(defn- any-access [_]
  true)

(defn- no-access [_]
  false)

(defn- authenticated [request]
  (if (-> request :session :identity :ticket)
    true
    (error unauthorized)))

(defn- has-oid?
  [permission oid]
  (= (permission "oid") oid))

(defn- match-oid-in-uri
  "This is a workaround for clout uri matching which matches against :path-info if it exists,
  rather than using full :uri."
  [request routes]
  (:oid (into {} (map #(clout/route-matches % {:path-info (:uri request)}) routes))))

(defn get-organizations-from-session [session]
  (get-in session [:identity :organizations]))

(defn- allowed-organization?
  [session oid]
  (some #(= (:oid %) oid) (get-organizations-from-session session)))

(defn oph-user?
  [session]
  (if (allowed-organization? session oph-oid)
    true))

(defn- oph-user-access
  "Checks if user is part of OPH organization."
  [request]
  (if (oph-user? (:session request))
    true
    (error forbidden)))

(defn- permission-to-organization
  "If request uri contains oid then it's checked that user has permissions for it."
  [request]
  (if-let [oid (match-oid-in-uri request organizer-routes)]
    (allowed-organization? (:session request) oid)
    true))

(defn- redirect-to-cas
  [request url-helper]
  (-> (found (url-helper :cas.login))
      (assoc :session {:success ((:query-params request) "callback")})))

(defn- redirect-to-shibboleth
  [request url-helper]
  (let [lang ((:query-params request) "lang")
        url-key (if lang
                  (str "tunnistus.url." lang)
                  "tunnistus.url.fi")]
    (-> (see-other (url-helper url-key))
        (assoc :session {:success ((:query-params request) "callback")}))))

(defn- rules
  "OPH users are allowed to call all endpoints without restrictions to organizer.
  Other users have access only to organizer they have permissions for."
  [url-helper]
  [{:pattern #".*/auth/cas/callback"
    :handler any-access}
   {:pattern #".*/auth/initsession"
    :handler any-access}
   {:pattern #".*/api/virkailija/organizer/.*/exam-session.*"
    :handler {:and [authenticated {:or [oph-user-access permission-to-organization]}]}}
   {:pattern #".*/api/virkailija/organizer"
    :handler authenticated ; authorized on database level
    :request-method :get}
   {:pattern #".*/api/virkailija/organizer.*"
    :handler {:and [authenticated oph-user-access]}
    :request-method #{:post :put :delete}}
   {:pattern #".*/auth/cas.*"
    :handler authenticated
    :on-error (fn [req _] (redirect-to-cas req url-helper))}
   {:pattern #".*/auth"
    :handler authenticated
    :on-error (fn [req _] (redirect-to-shibboleth req url-helper))}
   {:pattern #".*/api.*"
    :handler no-access}
   {:pattern #".*"
    :handler no-access}])

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper session-config]}]
  (defn with-authentication [handler]
    (-> handler
        (wrap-access-rules {:rules (rules url-helper)})
        (wrap-authentication backend)
        (wrap-authorization backend)
        (wrap-session {:store (cookie-store {:key (:key session-config)})
                       :cookie-name "yki"
                       :cookie-attrs (:cookie-attrs session-config)}))))

