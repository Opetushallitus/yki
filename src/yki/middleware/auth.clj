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

(defn- authenticated?
  [db cas-variant {:keys [session]}]
  (cond
    ; Email authentication allowed for public APIs
    (and (= :oppija cas-variant)
         (= "EMAIL" (:auth-method session)))
    true
    ; If authenticating through CAS- or CAS-Oppija, check for ticket validity
    (cas-ticket-db/get-ticket db cas-variant (-> session :identity :ticket))
    true
    :else
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
    (found (url-helper :cas.login.yki))
    :session
    {:success-redirect ((:query-params request) "success-redirect")}))

(defn oph-admin?
  [organizations]
  (allowed-organization-for-role? organizations oph-oid admin-role))

(defn oph-admin-access
  "Checks if user has YKI admin role for OPH organization"
  [request]
  (oph-admin? (get-organizations-from-session (:session request))))

(defn- redirect-to-cas-oppija
  [{:keys [query-params]} url-helper]
  (log/info "Redirect to cas-oppija")
  (let [{exam-session-id "examSessionId"
         query-lang      "lang"} query-params
        lang                     (or (#{"fi" "sv" "en"} query-lang)
                                     "fi")
        cas-success-redirect     (url-helper (str "cas-oppija.login-success." lang) exam-session-id)
        session-success-redirect (url-helper :yki-ui.exam-session-registration.url exam-session-id)
        login-url                (str (url-helper :cas-oppija.login lang) cas-success-redirect)]
    (assoc
      (see-other login-url)
      :session
      {:success-redirect session-success-redirect})))

(defn- rules
  "OPH users with admin role are allowed to call all endpoints without restrictions to organizer.
  Other users have access only to organizer they have permissions for."
  [url-helper db]
  (let [oppija-authenticated?     (fn [request]
                                    (authenticated? db :oppija request))
        virkailija-authenticated? (fn [request]
                                    (authenticated? db :virkailija request))]
    [{:pattern #".*/auth/cas/callback"
      :handler any-access}
     {:pattern #".*/auth/login.*"
      :handler any-access}
     {:pattern #".*/auth/logout"
      :handler any-access}
     {:pattern #".*/auth/logout/cas/callback"
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
     {:pattern        #".*/api/virkailija/organizer/.*/exam-date.*"
      :handler        virkailija-authenticated?
      :request-method :get}
     {:pattern        #".*/api/virkailija/organizer/.*/exam-date.*"
      :handler        {:and [virkailija-authenticated? oph-admin-access]}
      :request-method #{:post :put :delete}}
     {:pattern #".*/api/virkailija/.*/resend-confirmation-email"
      :handler {:and [virkailija-authenticated? oph-admin-access]}}
     {:pattern #".*/api/virkailija/organizer/.*/exam-session.*"
      :handler {:and [virkailija-authenticated? {:or [oph-admin-access permission-to-organization]}]}}
     {:pattern        #".*/api/virkailija/organizer"
      :handler        virkailija-authenticated?
      :request-method :get}
     {:pattern        #".*/api/virkailija/organizer.*"
      :handler        {:and [virkailija-authenticated? oph-admin-access]}
      :request-method #{:post :put :delete}}
     {:pattern #".*/api/virkailija/quarantine.*"
      :handler {:and [virkailija-authenticated? oph-admin-access]}}
     {:pattern #".*/api/virkailija/debug.*"
      :handler {:and [virkailija-authenticated? oph-admin-access]}}
     {:pattern        #".*/auth/cas.*"
      :handler        virkailija-authenticated?
      :on-error       (fn [req _] (redirect-to-cas req url-helper))
      :request-method :get}
     {:pattern        #".*/auth/cas"
      :handler        any-access
      :request-method :post}
     {:pattern  #".*/auth.*"
      :handler  no-access
      :on-error (fn [req _] (redirect-to-cas-oppija req url-helper))}
     {:pattern #".*/api/registration.*"
      :handler oppija-authenticated?}
     {:pattern #".*/api/exam-date/.*"
      :handler oph-admin-access}
     {:pattern #".*/api/payment/v2/report"
      :handler {:and [virkailija-authenticated? oph-admin-access]}}
     {:pattern #".*/api/payment/v3/paytrail/.*"
      :handler any-access}
     {:pattern #".*/api/payment/v3/.*/redirect"
      :handler oppija-authenticated?}
     {:pattern #".*/api/evaluation-payment/v3/.*"
      :handler any-access}
     {:pattern #".*/api/yki-register-debug/.*"
      :handler oph-admin-access}
     {:pattern #".*/api/user/identity"
      :handler any-access}
     {:pattern #".*/api/user/.*"
      :handler oppija-authenticated?}
     {:pattern #".*/api.*"
      :handler no-access}
     {:pattern #".*"
      :handler no-access}]))

(defn on-error
  [_request value]
  (or value {:headers {}, :status 403, :body "Forbidden"}))

(defn- wrap-session-timeout [handler timeout]
  (fn [request]
    (let [session-expiry (get-in request [:session :timeout])
          now            (quot (System/currentTimeMillis) 1000)
          ; Get response from downstream handler.
          ; Strip away session details if session is expired (or doesn't contain expiry time).
          response       (handler (cond-> request
                                          (or (nil? session-expiry)
                                              (< session-expiry now))
                                          (dissoc :session)))]
      (if-let [response-session (:session response)]
        (if (:timeout response-session)
          ; Don't renew session timeout if a timeout is present in response
          response
          ; Add session timeout to response otherwise.
          (assoc-in response [:session :timeout] (+ now timeout)))
        response))))

(defn- wrap-cas-session-clearing [handler db]
  (fn [request]
    (let [session     (:session request)
          auth-method (:auth-method session)
          ticket      (get-in session [:identity :ticket])
          cas-variant (case auth-method
                        "CAS"
                        :virkailija
                        "SUOMIFI"
                        :oppija
                        nil)]
      ; If auth-method is CAS or SUOMIFI and ticket is not found in database,
      ; the ticket has likely been deleted by CAS Single Logout.
      ; To respect the Single Logout, we must clear the session (cookie) here.
      (if (and cas-variant
               (not (cas-ticket-db/get-ticket db cas-variant ticket)))
        (handler (dissoc request :session))
        (handler request)))))

(defmethod ig/init-key :yki.middleware.auth/with-authentication [_ {:keys [url-helper session-config db]}]
  (let [{:keys [cookie-attrs key]} session-config]
    (fn with-authentication [handler]
      (-> handler
          (wrap-access-rules {:rules (rules url-helper db) :on-error on-error})
          (wrap-authentication backend)
          (wrap-authorization backend)
          (wrap-cas-session-clearing db)
          (wrap-session-timeout (:max-age cookie-attrs))
          ; TODO Figure out how to have separate cookies for public and virkailija APIs
          (wrap-session {:store        (cookie-store {:key (.getBytes ^String key)})
                         :cookie-name  "yki"
                         :cookie-attrs cookie-attrs})))))
