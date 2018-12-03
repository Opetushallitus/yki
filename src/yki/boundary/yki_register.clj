(ns yki.boundary.yki-register
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [yki.util.http-util :as http-util]
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
  [{:strs [languages contact_name contact_email]} {:strs [oid nimi postiosoite yhteystiedot]}]
  {:oid oid
   :nimi (or (nimi "fi") (nimi "sv") (nimi "en"))
   :katuosoite (postiosoite "osoite")
   :postinumero (last (str/split (postiosoite "postinumeroUri") #"_"))
   :postitoimipaikka (postiosoite "postitoimipaikka")
   :yhteyshenkilo contact_name
   :sposoite contact_email
   :wwwosoite (find-web-address yhteystiedot)
   :tutkintotarjonta (map (fn [x] {:tutkintokieli (x "language_code")
                                   :taso (convert-level (x "level_code"))}) languages)})

(defn create-sync-exam-session-req
  [{:keys [language_code level_code session_date office_oid organizer_oid]}]
  {:tutkintokieli language_code
   :taso (convert-level level_code)
   :pvm session_date
   :jarjestaja (or office_oid organizer_oid)})

(defn- sync-organizer
  [db url-helper disabled organizer-oid office-oid]
  (let [organizer (first (organizer-db/get-organizers-by-oids db [organizer-oid]))
        organization (organization/get-organization-by-oid url-helper (or office-oid organizer-oid))
        request (create-sync-organizer-req organizer organization)]
    (if disabled
      (log/info "Sending organizer" request))))

(defn- sync-exam-session
  [url-helper disabled exam-session]
  (let [request (create-sync-exam-session-req exam-session)]
    (if disabled
      (log/info "Sending exam session" request)
      (let [url                (url-helper :yki-register.exam-session)
            response           (http-util/do-post url {:headers {"content-type" "application/json; charset=UTF-8"}
                                                       :body    (json/write-value-as-string request)})]
        (when (and (not= 200 (:status response)) (not= 201 (:status response)))
          (println response)
          (throw (Exception. (str "Could not sync exam session " request))))))))

(defn sync-exam-session-and-organizer
  [db url-helper disabled exam-session-id]
  (let [{:keys [organizer_oid office_oid] :as exam-session} (exam-session-db/get-exam-session-by-id db exam-session-id)
        organizer-res (sync-organizer db url-helper disabled organizer_oid office_oid)
        exam-session-res (sync-exam-session url-helper disabled exam-session)]))
