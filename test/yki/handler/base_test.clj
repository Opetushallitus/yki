(ns yki.handler.base-test
  (:require [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [yki.boundary.files :as files]
            [muuntaja.middleware :as middleware]
            [compojure.core :refer :all]
            [peridot.core :as peridot]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.login-link :as login-link]
            [yki.handler.routing :as routing]
            [yki.handler.exam-session]
            [yki.handler.file]
            [yki.handler.registration]
            [yki.util.url-helper]
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

(def logout-request
  (slurp "test/resources/logoutRequest.xml"))

(def exam-session
  (slurp "test/resources/exam_session.json"))

(def payment-formdata-json
  (j/read-value (slurp "test/resources/payment_formdata.json")))

(defn change-entry
  [json-string key value]
  (j/write-value-as-string (assoc-in (j/read-value json-string) [key] value)))

(def payment-config {:paytrail-host "https://payment.paytrail.com/e2"
                     :yki-payment-uri "http://localhost:8080/yki/payment"
                     :merchant-id 12345
                     :amount "100.00"
                     :merchant-secret "SECRET_KEY"
                     :msg {:fi "msg_fi"
                           :sv "msg_sv"}})

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

(def select-participant "(SELECT id from participant WHERE external_user_id = 'test@user.com')")

(def select-exam-session "(SELECT id from exam_session WHERE max_participants = 50)")

(defn insert-login-link-prereqs []
  (insert-organizer "'1.2.3.4'")
  (insert-languages "'1.2.3.4'")
  (insert-exam-dates)
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
        exam_language_id,
        exam_date_id,
        max_participants,
        published_at)
          VALUES (
            (SELECT id FROM organizer where oid = '1.2.3.4'),
            (SELECT id from exam_language WHERE language_code = 'fi'), 1, 50, null)"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session_location (street_address,
    city,
    other_location_info,
    language_code,
    exam_session_id)
      VALUES (
        'Upseerinkatu 11',
        'Espoo',
        'Other info',
        'fi',
        (SELECT id FROM exam_session where max_participants = 50))"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id, email) VALUES ('test@user.com', 'test@user.com') ")))

(defn insert-payment []
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO registration(state, exam_session_id, participant_id) values ('SUBMITTED', " select-exam-session ", " select-participant ")"))
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO payment(state, registration_id, amount, lang, order_number) values ('UNPAID', (SELECT id FROM registration where state = 'SUBMITTED'), 100.00, 'fi', 'order1234')")))
(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
          (code, type, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
            VALUES ('" (login-link/sha256-hash code) "', 'REGISTRATION', " select-participant ", " select-exam-session ", '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

(defn insert-cas-ticket []
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO cas_ticketstore (ticket) VALUES ('ST-15126') ON CONFLICT (ticket) DO NOTHING")))

(defn select-one [query]
  (first (jdbc/query @embedded-db/conn query)))

(defn login-with-login-link [session]
  (-> session
      (peridot/request (str routing/auth-root "/login?code=" code-ok))))

(defn create-url-helper [uri]
  (ig/init-key :yki.util/url-helper {:virkailija-host uri :oppija-host uri :yki-host-virkailija uri :alb-host (str "http://" uri) :scheme "http"}))

(defn create-routes [port]
  (let [uri (str "localhost:" port)
        db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (create-url-helper uri)
        access-log (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db db})
        file-store (ig/init-key :yki.boundary.files/liiteri-file-store {:url-helper url-helper})
        auth (ig/init-key :yki.middleware.auth/with-authentication {:url-helper url-helper
                                                                    :db db
                                                                    :session-config {:key "ad7tbRZIG839gDo2"
                                                                                     :cookie-attrs {:max-age 28800
                                                                                                    :http-only true
                                                                                                    :domain "localhost"
                                                                                                    :secure false
                                                                                                    :path "/yki"}}})
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                             :db db
                                                                             :url-helper url-helper}))
        file-handler (ig/init-key :yki.handler/file {:db db :file-store file-store})
        organizer-handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                                       :url-helper url-helper
                                                                                       :exam-session-handler exam-session-handler
                                                                                       :file-handler file-handler}))]
    (routes organizer-handler auth-handler)))

(defn send-request-with-tx
  ([request]
   (send-request-with-tx request 8080))
  ([request port]
   ((create-routes port) request)))
