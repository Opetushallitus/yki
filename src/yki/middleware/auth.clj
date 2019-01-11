(ns yki.middleware.auth
  (:require
   [yki.handler.routing :as routing]
   [yki.boundary.cas-ticket-db :as cas-ticket-db]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [buddy.auth.accessrules :refer [wrap-access-rules success error]]
   [buddy.auth.backends.session :refer [session-backend]]
   [clojure.tools.logging :refer [warn]]
   [integrant.core :as ig]
   [clout.core :as clout]
   [ring.util.request :refer [request-url]]
   [ring.util.http-response :refer [unauthorized forbidden found see-other]])
  (:import [org.slf4j MDC]))

(def backend (session-backend))

(def oph-oid "1.2.246.562.10.00000000001")

(def admin-role "YLLAPITAJA")

(def organizer-role "JARJESTAJA")

(def organizer-routes
  ["*/organizer/:oid"
   "*/organizer/:oid/*"])

(defn- any-access [_]
  true)

(defn- no-access [request]
  (warn "No access to uri:" (:uri request))
  false)

(defn- participant-authenticated [request]
  (if-let [identity (-> request :session :identity)]
    (do
      (MDC/put "user" (:external-id identity))
      true)
    (error {:status 401 :body "Unauthorized"})))

(defn- virkailija-authenticated
  [db request]
  (if-let [ticket (cas-ticket-db/get-ticket db (-> request :session :identity :ticket))]
    (do
      (MDC/put "user" (:username identity))
      true)
    (error {:status 401 :body "Unauthorized"})))

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

(defn- allowed-organization-for-role?
  [session oid role]
  (if-let [organization (first (filter #(= (:oid %) oid) (get-organizations-from-session session)))]
    (let [permissions (:permissions organization)
          allowed (some #(= (:oikeus %) role) permissions)]
      allowed)))

(defn- permission-to-organization
  "If request uri contains oid then it's checked that user
  has admin or organizer permission for it."
  [request]
  (if-let [oid (match-oid-in-uri request organizer-routes)]
    (or (allowed-organization-for-role? (:session request) oid "YLLAPITAJA")
        (allowed-organization-for-role? (:session request) oid "JARJESTAJA"))
    true))

(defn- admin-permission-to-organization
  "If request uri contains oid then it's checked that user
  has admin permission for it."
  [request]
  (if-let [oid (match-oid-in-uri request organizer-routes)]
    (allowed-organization-for-role? (:session request) oid "YLLAPITAJA")
    true))

(defn- redirect-to-cas
  [request url-helper]
  (assoc
   (found (url-helper :cas.login))
   :session
   {:success-redirect ((:query-params request) "success-redirect")}))

(defn- redirect-to-shibboleth
  [request url-helper]
  (let [lang ((:query-params request) "lang")
        url-key (if lang
                  (str "tunnistus.url." lang)
                  "tunnistus.url.fi")]
    (assoc
     (see-other (url-helper url-key))
     :session
     {:success-redirect ((:query-params request) "success-redirect")})))

(defn- rules
  [url-helper db]
  [{:pattern #".*/auth/cas/callback"
    :handler any-access}
   {:pattern #".*/auth/login.*"
    :handler any-access}
   {:pattern #".*/auth/initsession"
    :handler any-access}
   {:pattern #".*/api/virkailija/organizer/.*/exam-session.*"
    :handler {:and [(partial virkailija-authenticated db) permission-to-organization]}}
   {:pattern #".*/api/virkailija/organizer"
    :handler (partial virkailija-authenticated db) ; authorized on database level
    :request-method :get}
   {:pattern #".*/api/virkailija/organizer/.*"
    :handler {:and [(partial virkailija-authenticated db) admin-permission-to-organization]}
    :request-method #{:post :put :delete}}
   {:pattern #".*/auth/cas.*"
    :handler (partial virkailija-authenticated db)
    :on-error (fn [req _] (redirect-to-cas req url-helper))
    :request-method :get}
   {:pattern #".*/auth/cas"
    :handler any-access
    :request-method :post}
   {:pattern #".*/auth.*"
    :handler participant-authenticated
    :on-error (fn [req _] (redirect-to-shibboleth req url-helper))}
   {:pattern #".*/api/registration.*"
    :handler participant-authenticated}
   {:pattern #".*/payment/formdata"
    :handler participant-authenticated}
   {:pattern #".*/payment/success"
    :handler participant-authenticated}
   {:pattern #".*/payment/cancel"
    :handler participant-authenticated}
   {:pattern #".*/payment/notify"
    :handler any-access}
   {:pattern #".*/api.*"
    :handler no-access}
   {:pattern #".*"
    :handler no-access}])

(defn on-error
  [request value]
  (if value
    value
    {:status 403
     :headers {}
     :body "Forbidden"}))

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper session-config db]}]

  (defn with-authentication [handler]
    (-> handler
        (wrap-access-rules {:rules (rules url-helper db) :on-error on-error})
        (wrap-authentication backend)
        (wrap-authorization backend)
        (wrap-session {:store (cookie-store {:key (:key session-config)})
                       :cookie-name "yki"
                       :cookie-attrs (:cookie-attrs session-config)}))))

