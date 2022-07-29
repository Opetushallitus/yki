(ns yki.middleware.auth
  (:require
    [buddy.auth.accessrules :refer [wrap-access-rules error]]
    [buddy.auth.backends.session :refer [session-backend]]
    [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
    [clojure.tools.logging :as log]
    [clout.core :as clout]
    [integrant.core :as ig]
    [ring.middleware.session :refer [wrap-session]]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.util.http-response :refer [found see-other]]
    [yki.boundary.cas-ticket-db :as cas-ticket-db]))

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
  (log/info "No access to uri:" (:uri request))
  false)

(defn- participant-authenticated [request]
  (if (-> request :session :identity)
    true
    (do
      (log/info "Participant not authenticated request uri:" (:uri request))
      (error {:status 401 :body "Unauthorized"}))))

(defn- virkailija-authenticated
  [db request]
  (if (cas-ticket-db/get-ticket db (-> request :session :identity :ticket))
    true
    (error {:status 401 :body "Unauthorized"})))

(defn- match-oid-in-uri
  "This is a workaround for clout uri matching which matches against :path-info if it exists,
  rather than using full :uri."
  [request routes]
  (:oid (into {} (map #(clout/route-matches % {:path-info (:uri request)}) routes))))

(defn get-organizations-from-session [session]
  (get-in session [:identity :organizations]))

(defn- allowed-organization-for-role?
  [organizations oid role]
  (when-let [organization (first (filter #(= (:oid %) oid) organizations))]
    (let [permissions (:permissions organization)
          allowed     (some #(= (:oikeus %) role) permissions)]
      allowed)))

(defn- permission-to-organization
  "If request uri contains oid then it's checked that user
  has admin or organizer permission for it."
  [request]
  (if-let [oid (match-oid-in-uri request organizer-routes)]
    (or (allowed-organization-for-role? (get-organizations-from-session (:session request)) oid admin-role)
        (allowed-organization-for-role? (get-organizations-from-session (:session request)) oid organizer-role))
    true))

(defn- redirect-to-cas
  [request url-helper]
  (assoc
    (found (url-helper :cas.login))
    :session
    {:success-redirect ((:query-params request) "success-redirect")}))

(defn oph-admin?
  [organizations]
  (allowed-organization-for-role? organizations oph-oid admin-role))

(defn- oph-admin-access
  "Checks if user has YKI admin role for OPH organization"
  [request]
  (oph-admin? (get-organizations-from-session (:session request))))

(defn- redirect-to-cas-oppija
  [request url-helper]
  (log/info "Redirect to cas-oppija")
  (let [exam-session-id ((:query-params request) "examSessionId")
        lang            (or (some #{((:query-params request) "lang")}
                                  ["fi" "sv" "en"])
                            "fi")
        success-url     (url-helper (str "cas-oppija.login-success." lang) exam-session-id)
        login-url       (str (url-helper :cas-oppija.login lang) success-url)]
    (assoc
      (see-other login-url)
      :session
      {:success-redirect (url-helper :exam-session.redirect exam-session-id lang)})))

(defn- rules
  "OPH users with admin role are allowed to call all endpoints without restrictions to organizer.
  Other users have access only to organizer they have permissions for."
  [url-helper db]
  [{:pattern #".*/auth/cas/callback"
    :handler any-access}
   {:pattern #".*/auth/login.*"
    :handler any-access}
   {:pattern #".*/auth/logout"
    :handler any-access}
   {:pattern #".*/auth/initsession"
    :handler any-access}
   {:pattern #".*/auth/user"
    :handler any-access}
   {:pattern #".*/auth/callback"
    :handler any-access}
   {:pattern        #".*/auth/callback.*"
    :handler        any-access
    :request-method :get}
   {:pattern        #".*/api/exam-session"
    :handler        any-access
    :request-method :get}
   {:pattern #".*/api/evaluation.*"
    :handler any-access}
   {:pattern        #".*/evaluation-payment/formdata"
    :handler        any-access
    :request-method :get}
   {:pattern        #".*/evaluation-payment/success"
    :handler        any-access
    :request-method :get}
   {:pattern        #".*/evaluation-payment/cancel"
    :handler        any-access
    :request-method :get}
   {:pattern        #".*/evaluation-payment/notify"
    :handler        any-access
    :request-method :get}
   {:pattern #".*/api/virkailija/organizer/.*/exam-session.*"
    :handler {:and [(partial virkailija-authenticated db) {:or [oph-admin-access permission-to-organization]}]}}
   {:pattern #".*/api/virkailija/organizer/.*/exam-date.*"
    :handler (partial virkailija-authenticated db)}
   {:pattern        #".*/api/virkailija/organizer"
    :handler        (partial virkailija-authenticated db)   ; authorized on database level
    :request-method :get}
   {:pattern        #".*/api/virkailija/organizer.*"
    :handler        {:and [(partial virkailija-authenticated db) oph-admin-access]}
    :request-method #{:post :put :delete}}
   {:pattern        #".*/api/virkailija/organizer/.*/file.*"
    :handler        {:and [(partial virkailija-authenticated db) oph-admin-access]}
    :request-method :get}
   {:pattern        #".*/auth/cas.*"
    :handler        (partial virkailija-authenticated db)
    :on-error       (fn [req _] (redirect-to-cas req url-helper))
    :request-method :get}
   {:pattern        #".*/auth/cas"
    :handler        any-access
    :request-method :post}
   {:pattern  #".*/auth.*"
    :handler  no-access
    :on-error (fn [req _] (redirect-to-cas-oppija req url-helper))}
   {:pattern #".*/api/registration.*"
    :handler participant-authenticated}
   {:pattern #".*/api/exam-date/.*"
    :handler oph-admin-access}
   {:pattern #".*/payment/formdata"
    :handler participant-authenticated}
   {:pattern #".*/payment/success"
    :handler participant-authenticated}
   {:pattern #".*/payment/cancel"
    :handler participant-authenticated}
   {:pattern #".*/payment/notify"
    :handler any-access}
   {:pattern #".*/api/payment/v2/paytrail/.*"
    :handler any-access}
   {:pattern #".*/api/payment/v2/.*/redirect"
    :handler participant-authenticated}
   {:pattern #".*/api.*"
    :handler no-access}
   {:pattern #".*"
    :handler no-access}])

(defn on-error
  [_request value]
  (or value {:headers {}, :status 403, :body "Forbidden"}))

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper session-config db]}]
  (fn with-authentication [handler]
    (-> handler
        (wrap-access-rules {:rules (rules url-helper db) :on-error on-error})
        (wrap-authentication backend)
        (wrap-authorization backend)
        (wrap-session {:store        (cookie-store {:key (.getBytes ^String (:key session-config))})
                       :cookie-name  "yki"
                       :cookie-attrs (:cookie-attrs session-config)}))))
