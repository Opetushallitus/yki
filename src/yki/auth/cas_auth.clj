(ns yki.auth.cas-auth
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info error]]
            [ring.util.http-response :refer [found see-other]]
            [yki.boundary.cas :as cas]
            [yki.boundary.cas-ticket-db :as cas-ticket-db]
            [yki.boundary.onr :as onr]
            [yki.boundary.permissions :as permissions]
            [yki.middleware.auth :as auth])
  (:import [java.util UUID]
           [clojure.data.xml Element]
           (fi.vm.sade.javautils.nio.cas CasLogout)))

(def unauthorized {:status  401
                   :body    "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- yki-permission? [permission]
  (= (:palvelu permission) "YKI"))

(defn- yki-permissions [org]
  {:oid         (:organisaatioOid org)
   :permissions (filter yki-permission? (:kayttooikeudet org))})

(defn- get-organizations-with-yki-permissions [organizations]
  (filter
    #(not-empty (:permissions %))
    (map yki-permissions organizations)))

(defn create-redirect-uri-from-session
  [session url-helper]
  (let [organizations (get-in session [:identity :organizations])
        oph-admin?    (auth/oph-admin? organizations)
        lang          (get-in session [:identity :lang])]
    (url-helper (if oph-admin? :yki.admin.cas.login-success.redirect
                               :yki.organizer.cas.login-success.redirect) lang)))

(defn virkailija-login [ticket request cas-client permissions-client onr-client url-helper db]
  (try
    (if ticket
      (let [auth-cas-client (cas-client (url-helper :cas-client))
            username        (cas/validate-ticket auth-cas-client ticket)
            _               (cas-ticket-db/create-ticket! db :virkailija ticket)
            permissions     (permissions/virkailija-by-username permissions-client username)
            person-oid      (:oidHenkilo permissions)
            person          (onr/get-person-by-oid onr-client person-oid)
            lang            (or (some #{(get-in person ["asiointiKieli" "kieliKoodi"])}
                                      ["fi" "sv"])
                                "fi")
            organizations   (get-organizations-with-yki-permissions (:organisaatiot permissions))
            oph-admin?      (auth/oph-admin? organizations)
            session         (:session request)
            redirect-uri    (if (:success-redirect session)
                              (str (:success-redirect session) "?lang=" lang)
                              (url-helper (if oph-admin? :yki.admin.cas.login-success.redirect
                                                         :yki.organizer.cas.login-success.redirect) lang))]
        (info "User" username "logged in")
        (if (empty? organizations)
          unauthorized
          (assoc
            (found redirect-uri)
            :session
            {:identity
             {:username      username
              :oid           person-oid
              :organizations organizations
              :lang          lang
              :ticket        ticket}
             :auth-method    "CAS"
             :yki-session-id (str (UUID/randomUUID))})))
      unauthorized)
    (catch Exception e
      (error e "Virkailija ticket handling failed")
      (throw e))))

(defn find-value
  "Find value in map"
  [m init-ks]
  ()
  (loop [c (get m (first init-ks)) ks (rest init-ks)]
    (if (empty? ks)
      c
      (let [k (first ks)]
        (recur
          (if (map? c)
            (get c k)
            (some #(get % k) c))
          (rest ks))))))

(defn xml->map [x]
  (hash-map
    (:tag x)
    (map
      #(if (= (type %) Element)
         (xml->map %)
         %)
      (:content x))))

(defn process-attributes [attributes]
  (into {} (for [m attributes
                 [k v] m]
             [k (str/join " " v)])))

(defn process-cas-attributes [response]
  (let [xml-response (-> response
                         (xml/parse-str)
                         (xml->map))
        success      (some?
                       (find-value xml-response
                                   [:serviceResponse
                                    :authenticationSuccess]))
        failure      (when-not success (find-value xml-response
                                                   [:serviceResponse
                                                    :authenticationFailure]))
        attributes   (find-value xml-response
                                 [:serviceResponse
                                  :authenticationSuccess
                                  :attributes])]
    (assoc (process-attributes attributes) :success? success :failureMessage failure)))

(defn oppija-login-response [exam-session-id session ticket cas-attributes url-helper onr-client]
  (let [{:keys [VakinainenKotimainenLahiosoitePostitoimipaikkaS
                VakinainenKotimainenLahiosoitePostinumero
                VakinainenKotimainenLahiosoiteS
                sn firstName nationalIdentificationNumber]} cas-attributes
        {:strs [etunimet
                sukunimi
                kutsumanimi
                oidHenkilo
                kansalaisuus]} (onr/get-person-by-ssn onr-client nationalIdentificationNumber)

        address      {:post_office    VakinainenKotimainenLahiosoitePostitoimipaikkaS
                      :zip            VakinainenKotimainenLahiosoitePostinumero
                      :street_address VakinainenKotimainenLahiosoiteS}
        redirect-uri (if (:success-redirect session)
                       (str (:success-redirect session))
                       (url-helper :yki-ui.exam-session-registration.url exam-session-id))]
    (info "Redirecting oppija to url: " redirect-uri)
    (if (and sn firstName nationalIdentificationNumber)
      (assoc
        (found redirect-uri)
        :session
        {:identity
         (merge
           {:first_name
            (if etunimet
              etunimet
              firstName)
            :last_name        (or sukunimi sn)
            :nick_name        kutsumanimi
            :ssn              nationalIdentificationNumber
            :oid              oidHenkilo
            :nationalities    (mapv #(get % "kansalaisuusKoodi") kansalaisuus)
            :external-user-id (or oidHenkilo nationalIdentificationNumber)
            :ticket           ticket}
           address)
         :auth-method    "SUOMIFI"
         :yki-session-id (str (UUID/randomUUID))})
      unauthorized)))

(defn- validation-failed-response [message exam-session-id lang url-helper]
  (info "Ticket validation failed: " message)
  (found (url-helper :exam-session.fail.redirect exam-session-id lang)))

(defn oppija-login [ticket request onr-client url-helper db]
  (try
    (info "Begin cas-oppija ticket handling: " ticket)
    (if ticket
      (let [{:strs [examSessionId]} (:query-params request)
            lang              (str/lower-case (or (some #{(-> request :route-params :*)}
                                                        ["FI" "SV" "EN"])
                                                  "fi"))
            callback-uri      (url-helper (str "cas-oppija.login-success." lang) examSessionId)
            cas-response      (cas/cas-oppija-ticket-validation url-helper ticket callback-uri)
            cas-attributes    (process-cas-attributes cas-response)
            session           (:session request)]
        (if (:success? cas-attributes)
          (do
            (cas-ticket-db/create-ticket! db :oppija ticket)
            (oppija-login-response examSessionId session ticket cas-attributes url-helper onr-client))
          (validation-failed-response (:failureMessage cas-attributes) examSessionId lang url-helper)))
      unauthorized)
    (catch Exception e
      (error e "Oppija cas ticket handling failed")
      (throw e))))

(defn cas-logout
  [db cas-variant logout-request]
  (try
    (let [ticket-container (-> (CasLogout.)
                               (.parseTicketFromLogoutRequest logout-request))]
      (if (.isEmpty ticket-container)
        (error "Could not parse ticket from CAS request")
        (cas-ticket-db/delete-ticket! db cas-variant (.get ticket-container))))
    (catch Exception e
      (error e "Caught exception parsing CAS logout request for variant" cas-variant))))

(defn logout
  [session url-helper]
  (info "user" (-> session :identity :username) "logged out")
  (assoc (found (url-helper :cas.logout.yki)) :session nil))

(defn oppija-logout [redirect-url]
  (info "Sending cas oppija logout to" redirect-url)
  (assoc (see-other redirect-url) :session nil))
