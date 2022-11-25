(ns yki.boundary.yki-register
  (:require
    [clojure.data.csv :as csv]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [yki.util.http-util :as http-util]
    [yki.boundary.organizer-db :as organizer-db]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.organization :as organization]
    [yki.boundary.codes :as codes]
    [jsonista.core :as json])
  (:import (java.io StringWriter)))

; HOTFIX change: if nationality null or empty, its labeled as missing and thus marked as "xxx".
; Find out why Solki and yki nationality codes differ sometimes, to avoid using a blacklist like this
(defn- nationality-not-supported-or-missing? [nationality]
  (some #(= nationality %) ["ZAR" "YYY" "XKK" "" nil]))

(defn- convert-level [level]
  (case level "PERUS" "PT" "KESKI" "KT" "YLIN" "YT"))

(defn- find-web-address [contacts]
  (let [www-contact (first (filter #(some? (% "www")) contacts))]
    (if www-contact (www-contact "www") "")))

(defn create-sync-organizer-req
  [{:keys [languages contact_name contact_phone_number contact_email]} {:strs [oid nimi postiosoite yhteystiedot]}]
  {:oid              oid
   :nimi             (or (nimi "fi") (nimi "sv") (nimi "en"))
   :katuosoite       (postiosoite "osoite")
   :postinumero      (last (str/split (postiosoite "postinumeroUri") #"_"))
   :puhelin          contact_phone_number
   :postitoimipaikka (postiosoite "postitoimipaikka")
   :yhteyshenkilo    contact_name
   :sposoite         contact_email
   :wwwosoite        (find-web-address yhteystiedot)
   :tutkintotarjonta (map (fn [l] {:kieli (:language_code l)
                                   :taso  (convert-level (:level_code l))}) languages)})

(defn create-sync-exam-session-req
  [{:keys [language_code level_code session_date office_oid organizer_oid]}]
  {:kieli      language_code
   :taso       (convert-level level_code)
   :pvm        session_date
   :jarjestaja (or office_oid organizer_oid)})

(defn create-sync-exam-date-req
  [{:keys [language_code session_date]}]
  {:kieli language_code
   :pvm   session_date})

(defn- remove-basic-auth [response]
  (update-in response [:opts] dissoc :basic-auth))

(defn- do-post
  ([url body-as-string basic-auth]
   (do-post url body-as-string basic-auth "application/json; charset=UTF-8"))
  ([url body-as-string basic-auth content-type]
   (log/info "POST to url" url)
   (let [response (http-util/do-post url {:headers    {"content-type" content-type}
                                          :basic-auth [(:user basic-auth) (:password basic-auth)]
                                          :body       body-as-string})
         status   (str (:status response))]
     (if (or (str/starts-with? status "2") (str/starts-with? status "3"))
       (log/info "Syncing data success")
       (do
         (log/error "Failed to sync data, error response" (remove-basic-auth response))
         (throw (Exception. (str "Could not sync request to url " url))))))))

(defn- do-delete [url basic-auth]
  (log/info "DELETE request to url" url)
  (let [response (http-util/do-delete url {:basic-auth [(:user basic-auth) (:password basic-auth)]})
        status   (str (:status response))]
    (if (or (str/starts-with? status "2") (str/starts-with? status "3") (= status "404"))
      (log/info "Deleting data success" (remove-basic-auth response))
      (do
        (log/error "Failed to sync data, error response" (remove-basic-auth response))
        (throw (Exception. (str "Could not sync deletion " url)))))))

(defn- sync-organizer
  [db url-helper basic-auth disabled organizer-oid office-oid]
  (let [organizer    (first (organizer-db/get-organizers-by-oids db [organizer-oid]))
        organization (when (= disabled false) (organization/get-organization-by-oid url-helper (or office-oid organizer-oid)))
        request      (when (= disabled false) (create-sync-organizer-req organizer organization))]
    (if disabled
      (log/info "Organizer sync sending disabled.")
      (do-post (url-helper :yki-register.organizer) (json/write-value-as-string request) basic-auth))))

(defn- create-url-params
  [{:keys [language_code level_code session_date office_oid organizer_oid]}]
  (str
    "?kieli=" language_code
    "&taso=" (convert-level level_code)
    "&pvm=" session_date
    "&jarjestaja=" (or office_oid organizer_oid)))

(defn- remove-organizer [url-helper basic-auth disabled oid]
  (if disabled
    (log/info "Sending disabled. Logging delete" oid)
    (do-delete (str (url-helper :yki-register.organizer) "?oid=" oid) basic-auth)))

(defn- remove-exam-session [url-helper basic-auth disabled exam-session]
  (if disabled
    (log/info "Sending disabled. Logging delete" exam-session)
    (do-delete (str (url-helper :yki-register.exam-session)
                    (create-url-params exam-session)) basic-auth)))

(defn- ssn-or-birthdate [ssn birthdate]
  (if-not (str/blank? ssn)
    ssn
    (let [[year month day] (str/split birthdate #"-")]
      (str
        day
        month
        (subs year 2 4)
        (if (< (Integer/valueOf ^String year) 2000) "-" "A")))))

(defn- convert-gender
  [gender ssn]
  (if-not (str/blank? ssn)
    (let [identifier (Integer/valueOf (subs ssn 7 10))
          remainder  (rem identifier 2)]
      (if (= remainder 1)
        "M"
        "N"))
    (case gender
      "1" "M"
      "2" "N"
      "E")))

(defn- sync-exam-session
  [url-helper basic-auth disabled exam-session]
  (let [exam-session-req (create-sync-exam-session-req exam-session)
        exam-date-req    (create-sync-exam-date-req exam-session)]
    (if disabled
      (do
        (log/info "Sending disabled. Logging exam session request" exam-session-req)
        (log/info "Sending disabled. Logging exam date request" exam-date-req))
      (do
        (do-post (url-helper :yki-register.exam-date) (json/write-value-as-string exam-date-req) basic-auth)
        (do-post (url-helper :yki-register.exam-session) (json/write-value-as-string exam-session-req) basic-auth)))))

(defn participant->csv-record [url-helper registration-form oid]
  (let [{:keys [first_name last_name gender nationalities birthdate ssn certificate_lang
                exam_lang post_office zip street_address email]} registration-form
        nationality (codes/get-converted-country-code url-helper (first nationalities))]
    [oid
     (ssn-or-birthdate ssn birthdate)
     last_name
     first_name
     (convert-gender gender ssn)
     (if (nationality-not-supported-or-missing? nationality) "xxx" nationality)
     street_address
     zip
     post_office
     email
     exam_lang
     certificate_lang]))

(defn create-participants-csv [url-helper participants]
  (with-open [writer (StringWriter.)]
    (let [csv-data (map #(participant->csv-record url-helper (:form %) (:person_oid %)) participants)]
      (csv/write-csv writer csv-data :separator \;))
    (.toString writer)))

(defn sync-exam-session-participants
  [db url-helper basic-auth disabled exam-session-id]
  (let [exam-session (exam-session-db/get-exam-session-by-id db exam-session-id)
        participants (exam-session-db/get-completed-exam-session-participants db exam-session-id)
        url          (str (url-helper :yki-register.participants)
                          (create-url-params exam-session))
        request      (create-participants-csv url-helper participants)]
    (exam-session-db/init-participants-sync-status! db exam-session-id)
    (if disabled
      (log/info "Sending disabled. Logging request" request)
      (do-post url request basic-auth "text/csv; charset=UTF-8"))
    (exam-session-db/set-participants-sync-to-success! db exam-session-id)))

(defn sync-exam-session-and-organizer
  "When exam session is synced to YKI register then also organizer data is synced."
  [db url-helper basic-auth disabled {:keys [type exam-session organizer-oid]}]
  (case type
    "DELETE" (if exam-session
               (remove-exam-session url-helper basic-auth disabled exam-session)
               (remove-organizer url-helper basic-auth disabled organizer-oid))
    (if exam-session
      (let [{:keys [organizer_oid office_oid]} exam-session]
        (sync-organizer db url-helper basic-auth disabled organizer_oid office_oid)
        (sync-exam-session url-helper basic-auth disabled exam-session))
      (sync-organizer db url-helper basic-auth disabled organizer-oid nil))))
