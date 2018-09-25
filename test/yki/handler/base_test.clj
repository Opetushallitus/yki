(ns yki.handler.base-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [yki.boundary.files :as files]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.exam-session]
            [yki.handler.file]
            [yki.handler.organizer]))

(defrecord MockStore []
  files/FileStore
  (upload-file [_ _ _]
    {"key" "d45c5262"}))

(def organization {:oid "1.2.3.4"
                   :agreement_start_date "2018-01-01T00:00:00Z"
                   :agreement_end_date "2029-01-01T00:00:00Z"
                   :contact_email "fuu@bar.com"
                   :contact_name "fuu"
                   :contact_phone_number "123456"
                   :languages [{:language_code "fi" :level_code "PERUS"},
                               {:language_code "en" :level_code "PERUS"}]})

(def organizations-json
  (j/read-value (slurp "test/resources/organizers.json")))

(defn insert-organization [tx oid]
  (jdbc/execute! tx (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, contact_shared_email)
        VALUES (" oid ", '2018-01-01', '2019-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi')")))

(defn insert-languages [tx oid]
  (jdbc/execute! tx (str "insert into exam_language (language_code, level_code, organizer_id) values ('fi', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))"))
  (jdbc/execute! tx (str "insert into exam_language (language_code, level_code, organizer_id) values ('sv', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))")))

(defrecord MockStore []
  files/FileStore
  (upload-file [_ _ _]
    {"key" "d45c5262"}))

(defn send-request [tx request]
  (jdbc/db-set-rollback-only! tx)
  (let [db (duct.database.sql/->Boundary tx)
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db db})
        file-handler (ig/init-key :yki.handler/file {:db db :file-store (->MockStore)})
        handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                             :url-helper {}
                                                                             :exam-session-handler exam-session-handler
                                                                             :file-handler file-handler}))]
    (handler request)))
