(ns yki.handler.base-test
  (:require [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [yki.boundary.files :as files]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.login-link :as login-link]
            [yki.handler.routing :as routing]
            [yki.handler.exam-session]
            [yki.handler.file]
            [yki.handler.organizer]))

(def code-ok "4ce84260-3d04-445e-b914-38e93c1ef667")

(def organizer {:oid "1.2.3.4"
                :agreement_start_date "2018-01-01T00:00:00Z"
                :agreement_end_date "2049-01-01T00:00:00Z"
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

(def payment-formdata-json
  (j/read-value (slurp "test/resources/payment_formdata.json")))

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
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('fi', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('sv', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))")))

(defn insert-exam-dates []
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2039-05-02', '2039-05-01', '2039-12-01')"))

(defn insert-login-link-prereqs []
  (insert-organizer "'1.2.3.4'")
  (insert-languages "'1.2.3.4'")
  (insert-exam-dates)
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
        exam_language_id,
        exam_date_id,
        max_participants,
        published_at)
          VALUES (1, 1, 1, 50, null)"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id) VALUES ('test@user.com') "))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO registration(state, exam_session_id, participant_id) values ('INCOMPLETE', 1, 1)"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO payment(state, registration_id, amount, reference_number, order_number) values ('UNPAID', 1, 100.00, 312321325, 'order1234')")))

(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
          (code, type, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
            VALUES ('" (login-link/sha256-hash code) "', 'REGISTRATION', 1, 1, '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

(defn login-with-login-link [session]
  (-> session
      (peridot/request (str routing/auth-root "/login?code=" code-ok))))

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
