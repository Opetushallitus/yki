(ns yki.boundary.onr
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [yki.boundary.cas :as cas]))

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
  [{:keys [email first_name last_name preferred_name gender exam_lang nationalities native_language birthdate ssn]
    :as   registration}
   attempt]
  (let [basic-fields (cond->
                       {:yhteystieto   [{:yhteystietoTyyppi "YHTEYSTIETO_SAHKOPOSTI"
                                         :yhteystietoArvo   email}]
                        :etunimet      first_name
                        :kutsumanimi   (or preferred_name (first-names->nickname first_name))
                        :sukunimi      last_name
                        :sukupuoli     (if (str/blank? gender) nil gender)
                        :asiointiKieli {:kieliKoodi exam_lang}
                        :kansalaisuus  (extract-nationalities nationalities)
                        :henkiloTyyppi "OPPIJA"}
                       native_language
                       (assoc :aidinkieli {:kieliKoodi (str/lower-case native_language)}))]
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
  (get-person-by-oid [this oid])
  (list-persons-by-oids [this oids])
  (list-ssn-by-oids [this oids]))

(defn- get-or-create-person-with-retries [cas-client onr-url registration max-attempts]
  (loop [attempt 1
         val     nil]
    (if (< max-attempts attempt)
      (do
        (log/error
          (str "Retries exhausted. Returning last match. Registration id: "
               (:registration_id registration)
               ", OID: "
               (val "oidHenkilo")))
        val)
      (let [onr-person (registration->onr-person registration attempt)
            response   (cas/cas-authenticated-post cas-client onr-url onr-person)
            status     (response->status response)]
        (cond

          ; New person created within ONR -> return response body
          (= 201 status)
          (response->body response)

          ; Found an existing person in ONR -> check and return details if they look like a reasonable match
          ; Otherwise try again with slightly altered identification details to avoid using someone else's OID.
          (= 200 status)
          (let [json-body (response->body response)]
            (if (returned-person-details-match? registration json-body)
              json-body
              (recur (inc attempt) json-body)))

          ; Else log error
          :else
          (log/error "ONR get-or-create-person request:" (str onr-person " status: " status " : " (:body response))))))))

(defn- get-or-create-person-from-cas-attributes [cas-client onr-url {:keys [first_name last_name ssn]}]
  (let [onr-person {:henkiloTyyppi      "OPPIJA"
                    :etunimet           first_name
                    :kutsumanimi        (first-names->nickname first_name)
                    :sukunimi           last_name
                    :hetu               (str/upper-case ssn)
                    :eiSuomalaistaHetua false}
        response   (cas/cas-authenticated-post cas-client onr-url onr-person)
        status     (response->status response)]
    (case status
      ; Created new person or returned existing (unlikely, but perhaps possible due to eg. race conditions)
      (200 201)
      (response->body response)
      ; Other response statuses are unexpected
      (log/error "ONR get-or-create-person-from-cas-attributes failed:" (str onr-person " status: " status " : " (:body response))))))

(defn- is-registration-form-data? [person]
  (some? (:email person)))

(defrecord OnrClient [url-helper cas-client]
  Onr
  (get-or-create-person [_ person]
    (let [url   (url-helper :onr-service.get-or-create-person)
          tries 3]
      (if (is-registration-form-data? person)
        (get-or-create-person-with-retries cas-client url person tries)
        (get-or-create-person-from-cas-attributes cas-client url person))))
  (get-person-by-oid [_ oid]
    (let [url (url-helper :onr-service.person-by-oid oid)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (log/error "ONR get-person-by-oid error:" status))))
  (list-persons-by-oids [_ oids]
    {:pre [(coll? oids)
           (seqable? oids)
           (counted? oids)
           (<= 5000 (count oids))]}
    (let [url (url-helper :onr-service.list-persons-by-oids)
          {:keys [status body]} (cas/cas-authenticated-post cas-client url oids)]
      (if (= 200 status)
        (json/read-value body)
        (log/error "ONR list-persons-by-oids error:" status))))
  (list-ssn-by-oids [_ oids]
    {:pre [(coll? oids)
           (seqable? oids)
           (counted? oids)
           (<= 5000 (count oids))]}
    (if (empty? oids)
      []
      (let [url (url-helper :onr-service.list-person-details)
            {:keys [status body]} (cas/cas-authenticated-post cas-client url {:henkiloOids oids})]
        (if (= 200 status)
          (->> (json/read-value body)
               (map #(vector
                       (get % "oidHenkilo")
                       (get % "hetu")))
               (into {}))
          (log/error "ONR list-ssn-by-oids error:" status))))))

(defmethod ig/init-key :yki.boundary.onr/onr-client [_ {:keys [url-helper cas-client]}]
  (let [onr-cas-client (cas-client (url-helper :onr-service))]
    (->OnrClient url-helper onr-cas-client)))
