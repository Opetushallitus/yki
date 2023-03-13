(ns yki.boundary.onr
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [yki.boundary.cas-access :as cas]))

(defn- extract-nationalities
  [nationalities]
  (map (fn [n] {:kansalaisuusKoodi n}) nationalities))

(defn has-ssn? [{:keys [ssn]}]
  (not (str/blank? ssn)))

(defn first-names->nickname [first-names]
  (->> (str/split first-names #" ")
       (first)))

(defn normalize-identifier [identifier]
  (-> identifier
      (str/replace #"\s+" "")
      (str/lower-case)))

(defn- registration+attempt->identifications [{:keys [email last_name first_name birthdate]} ^long attempt]
  (case attempt
    1
    [{:idpEntityId "oppijaToken"
      :identifier
      email}]
    2
    [{:idpEntityId "oppijaToken"
      :identifier  (->> [last_name (first-names->nickname first_name) birthdate]
                        (str/join "_")
                        (normalize-identifier))}]
    ; As a last resort, return empty identifications to ensure a new person is created.
    nil))

(defn- registration->onr-person
  [{:keys [email first_name last_name gender exam_lang nationalities birthdate ssn]
    :as   registration}
   attempt]
  (let [basic-fields {:yhteystieto   [{:yhteystietoTyyppi "YHTEYSTIETO_SAHKOPOSTI"
                                       :yhteystietoArvo   email}]
                      :etunimet      first_name
                      :kutsumanimi   (first-names->nickname first_name)
                      :sukunimi      last_name
                      :sukupuoli     (if (str/blank? gender) nil gender)
                      :asiointiKieli {:kieliKoodi exam_lang}
                      :kansalaisuus  (extract-nationalities nationalities)
                      :henkiloTyyppi "OPPIJA"}]
    (if (has-ssn? {:ssn ssn})
      (assoc
        basic-fields
        :hetu (str/upper-case ssn)
        :eiSuomalaistaHetua false)
      (assoc
        basic-fields
        :syntymaaika birthdate
        :identifications (registration+attempt->identifications registration attempt)
        :eiSuomalaistaHetua true))))

(defn- returned-person-details-match? [registration person-response]
  (cond
    ; SSN supplied for person -> trust the OID returned, but log a warning if other details do not match!
    (has-ssn? registration)
    (do
      (when-not (= (:last_name registration)
                   (person-response "sukunimi"))
        (log/warn
          (str "Found OID for registration with hetu, but supplied and returned last names differ. Registration id: "
               (:registration_id registration)
               ", OID: "
               (person-response "oidHenkilo")
               ", supplied: "
               (:last_name registration)
               ", returned: "
               (person-response "sukunimi"))))
      true)
    ; Use birthdate as the next best criteria to gauge if the person in ONR matches the one on our registration form
    (= (:birthdate registration) (person-response "syntymaaika"))
    true
    ; Unmatching birthdates => infer a mismatch and log a warning
    :else
    (do
      (log/warn
        (str "Found OID but supplied and returned birthdates differ. Registration id: "
             (:registration_id registration)
             ", OID: "
             (person-response "oidHenkilo")
             ", supplied: "
             (:birthdate registration)
             ", returned: "
             (person-response "syntymaaika")))
      false)))

(defn response->body [response]
  (json/read-value (:body response)))

(defn response->status [response]
  (:status response))

(defprotocol Onr
  (get-or-create-person [this person])
  (get-person-by-ssn [this ssn])
  (get-person-by-oid [this oid]))

(defn- get-or-create-person-with-retries [cas-client onr-url registration max-attempts]
  (loop [attempt 1
         oid     nil]
    (if (< max-attempts attempt)
      (do
        (log/error
          (str "Retries exhausted. Returning OID of last match. Registration id: "
               (:registration_id registration)
               ", OID: "
               oid))
        oid)
      (let [onr-person (registration->onr-person registration attempt)
            response   (cas/cas-authenticated-post cas-client onr-url onr-person)
            status     (response->status response)]
        (cond

          ; New person created within ONR -> return the OID returned in response
          (= 201 status)
          ((response->body response) "oidHenkilo")

          ; Found an existing person in ONR -> check details and return OID if they look like a reasonable match
          ; Otherwise try again with slightly altered identification details to avoid using someone else's OID.
          (= 200 status)
          (let [json-body (response->body response)
                oid       (json-body "oidHenkilo")]
            (if (returned-person-details-match? registration json-body)
              oid
              (recur (inc attempt) oid)))

          ; Else log error
          :else
          (log/error "ONR get-or-create-person request:" (str onr-person " status: " status " : " (:body response))))))))

(defrecord OnrClient [url-helper cas-client]
  Onr
  (get-or-create-person [_ registration]
    (let [url   (url-helper :onr-service.get-or-create-person)
          tries 3]
      (get-or-create-person-with-retries cas-client url registration tries)))
  (get-person-by-ssn [_ ssn]
    (let [url (url-helper :onr-service.person-by-ssn ssn)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (log/info "ONR get-person-by-ssn error:" status))))
  (get-person-by-oid [_ oid]
    (let [url (url-helper :onr-service.person-by-oid oid)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (log/error "ONR get-person-by-oid error:" status)))))

(defmethod ig/init-key :yki.boundary.onr/onr-client [_ {:keys [url-helper cas-client]}]
  (->OnrClient url-helper (cas-client "/oppijanumerorekisteri-service")))
