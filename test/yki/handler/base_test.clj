(ns yki.handler.base-test
  (:require [integrant.core :as ig]
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

(def organizer {:oid "1.2.3.4"
                :agreement_start_date "2018-01-01T00:00:00Z"
                :agreement_end_date "2029-01-01T00:00:00Z"
                :contact_email "fuu@bar.com"
                :contact_name "fuu"
                :contact_phone_number "123456"
                :contact_shared_email "shared@oph.fi"
                :languages [{:language_code "fi" :level_code "PERUS"},
                            {:language_code "en" :level_code "PERUS"}]})

(def organizers-json
  (j/read-value (slurp "test/resources/organizers.json")))

(def exam-sessions-json
  (j/read-value (slurp "test/resources/exam_sessions.json")))

(def exam-session
  (slurp "test/resources/exam_session.json"))

(defn change-entry
  [json-string key value]
  (j/write-value-as-string (assoc-in (j/read-value json-string) [key] value)))

(defn cas-mock-routes [port]
  {"/cas/v1/tickets" {:status 201
                      :method :post
                      :headers {"Location" (str "http://localhost:" port "/cas/v1/tickets/TGT-1-FFDFHDSJK")}
                      :body "ST-1-FFDFHDSJK2"}
   "/cas/v1/tickets/TGT-1-FFDFHDSJK" {:status 200
                                      :method :post
                                      :body "ST-1-FFDFHDSJK2"}})

(defn body-as-json [response]
  (j/read-value (slurp (:body response) :encoding "UTF-8")))

(defn insert-organizer [oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, contact_shared_email)
        VALUES (" oid ", '2018-01-01', '2089-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi')")))

(defn insert-languages [oid]
  (jdbc/execute! @embedded-db/conn (str "insert into exam_language (language_code, level_code, organizer_id) values ('fi', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))"))
  (jdbc/execute! @embedded-db/conn (str "insert into exam_language (language_code, level_code, organizer_id) values ('sv', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))")))

(defn insert-login-link-prereqs []
  (insert-organizer "'1.2.3.4'")
  (insert-languages "'1.2.3.4'")
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
        exam_language_id,
        session_date,
        session_start_time,
        session_end_time,
        registration_start_date,
        registration_start_time,
        registration_end_date,
        registration_end_time,
        max_participants,
        published_at)
          VALUES (1, 1, '2028-01-01', null, null, null, null, null, null, 50, null)"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id) VALUES ('test@user.com') ")))

(defn send-request-with-tx
  ([request]
   (send-request-with-tx request ""))
  ([request port]
   (let [uri (str "localhost:" port)
         db (duct.database.sql/->Boundary @embedded-db/conn)
         url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri :oppija-host uri :yki-host-virkailija uri :alb-host (str "http://" uri) :scheme "http"})
         exam-session-handler (ig/init-key :yki.handler/exam-session {:db db})
         file-store (ig/init-key :yki.boundary.files/liiteri-file-store {:url-helper url-helper})
         file-handler (ig/init-key :yki.handler/file {:db db :file-store file-store})
         handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                              :url-helper url-helper
                                                                              :exam-session-handler exam-session-handler
                                                                              :file-handler file-handler}))]
     (handler request))))
