(ns yki.boundary.yki-register
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [yki.util.http-util :as http-util]
            [clj-time.format :as f]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.organization :as organization]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]))

(defn- convert-level [level]
  (case level "PERUS" "PT" "KESKI" "KT" "YLIN" "YT"))

(defn- find-web-address [contacts]
  ((first (filter #(some? (% "www")) contacts)) "www"))

(defn create-sync-organizer-req
  [{:keys [languages contact_name contact_email]} {:strs [oid nimi postiosoite yhteystiedot]}]
  {:oid oid
   :nimi (or (nimi "fi") (nimi "sv") (nimi "en"))
   :katuosoite (postiosoite "osoite")
   :postinumero (last (str/split (postiosoite "postinumeroUri") #"_"))
   :postitoimipaikka (postiosoite "postitoimipaikka")
   :yhteyshenkilo contact_name
   :sposoite contact_email
   :wwwosoite (find-web-address yhteystiedot)
   :tutkintotarjonta (map (fn [l] {:tutkintokieli (:language_code l)
                                   :taso (convert-level (:level_code l))}) languages)})

(defn create-sync-exam-session-req
  [{:keys [language_code level_code session_date office_oid organizer_oid]}]
  {:tutkintokieli language_code
   :taso (convert-level level_code)
   :pvm session_date
   :jarjestaja (or office_oid organizer_oid)})

(defn- do-post
  ([url body-as-string]
   (do-post url body-as-string "application/json; charset=UTF-8"))
  ([url body-as-string content-type]
   (let [response (http-util/do-post url {:headers {"content-type" content-type}
                                          :body    body-as-string})
         status (:status response)]
     (when (and (not= 200 status) (not= 201 status))
       (log/error "Failed to sync data, error response" response)
       (throw (Exception. (str "Could not sync request " body-as-string)))))))

(defn- do-delete [url]
  (let [response (http-util/do-delete url)
        status (:status response)]
    (when (and (not= 200 status) (not= 202 status))
      (log/error "Failed to sync data, error response" response)
      (throw (Exception. (str "Could not sync deletion " url))))))

(defn- sync-organizer
  [db url-helper disabled organizer-oid office-oid]
  (let [organizer (first (organizer-db/get-organizers-by-oids db [organizer-oid]))
        organization (organization/get-organization-by-oid url-helper (or office-oid organizer-oid))
        request (create-sync-organizer-req organizer organization)]
    (if disabled
      (log/info "Sending disabled. Logging request " request)
      (do-post (url-helper :yki-register.organizer) (json/write-value-as-string request)))))

(defn- create-url-params
  [{:keys [language_code level_code session_date office_oid organizer_oid]}]
  (str
   "?tutkintokieli=" language_code
   "&taso=" (convert-level level_code)
   "&pvm=" session_date
   "&jarjestaja=" (or office_oid organizer_oid)))

(defn- remove-organizer [url-helper disabled oid]
  (if disabled
    (log/info "Sending disabled. Logging delete" oid)
    (do-delete (str (url-helper :yki-register.organizer) "?oid=" oid))))

(defn- remove-exam-session [url-helper disabled  exam-session]
  (if disabled
    (log/info "Sending disabled. Logging delete" exam-session)
    (do-delete (str (url-helper :yki-register.exam-session)
                    (create-url-params exam-session)))))

(defn- ssn-or-birthdate [ssn birthdate]
  (or ssn
      (let [[year month day] (str/split birthdate #"-")]
        (str
         day
         month
         (subs year 2 4)
         (if (< (Integer/valueOf year) 2000) "-" "A")))))

(defn- convert-gender
  [gender ssn]
  (if ssn
    (let [identifier (Integer/valueOf (subs ssn 7 10))
          remainder (rem identifier 2)]
      (if (= remainder 1)
        "M"
        "N"))
    (case gender
      "1" "M"
      "2" "N"
      "")))

(defn- sync-exam-session
  [url-helper disabled exam-session]
  (let [request (create-sync-exam-session-req exam-session)]
    (if disabled
      (log/info "Sending disabled. Logging request" request)
      (do-post (url-helper :yki-register.exam-session) (json/write-value-as-string request)))))

(defn create-partipant-csv [registration-form oid]
  (let [{:keys [first_name last_name gender nationalities birth_date ssn certificate_lang
                exam_lang post_office zip street_address phone_number email]} registration-form]
    (str oid ";"
         (ssn-or-birthdate ssn birth_date) ";"
         first_name ";"
         last_name ";"
         (convert-gender gender ssn) ";"
         (first nationalities) ";"
         street_address ";"
         zip ";"
         post_office ";"
         email ";"
         exam_lang ";"
         certificate_lang)))

(defn create-participants-csv [participants]
  (map #(create-partipant-csv (:form %) (:person_oid %)) participants))

(defn sync-exam-session-participants
  [db url-helper disabled exam-session-id]
  (let [exam-session (exam-session-db/get-exam-session-by-id db exam-session-id)
        participants (exam-session-db/get-completed-exam-session-participants db exam-session-id)
        url (str (url-helper :yki-register.participants)
                 (create-url-params exam-session))
        request (str/join (System/lineSeparator) (create-participants-csv participants))]
    (if disabled
      (log/info "Sending disabled. Logging participants" participants)
      (do
        (exam-session-db/init-participants-sync-status! db exam-session-id)
        (do-post url request "text/csv; charset=UTF-8")
        (exam-session-db/set-participants-sync-to-success! db exam-session-id)))))

(defn sync-exam-session-and-organizer
  "When exam session is synced to YKI register then also organizer data is synced."
  [db url-helper disabled {:keys [type exam-session-id organizer-oid]}]
  (case type
    "DELETE" (if exam-session-id
               (remove-exam-session url-helper disabled (exam-session-db/get-exam-session-by-id db exam-session-id))
               (remove-organizer url-helper disabled organizer-oid))
    (if exam-session-id
      (let [{:keys [organizer_oid office_oid] :as exam-session} (exam-session-db/get-exam-session-by-id db exam-session-id)]
        (if exam-session
          (do
            (sync-organizer db url-helper disabled organizer_oid office_oid)
            (sync-exam-session url-helper disabled exam-session))
          (log/warn "Exam session not found id:" exam-session-id)))
      (sync-organizer db url-helper disabled organizer-oid nil))))
