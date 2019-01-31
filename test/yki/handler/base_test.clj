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
            [yki.handler.auth]
            [yki.job.job-queue]
            [yki.middleware.no-auth]
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
                :extra "shared@oph.fi"
                :languages [{:language_code "fin" :level_code "PERUS"},
                            {:language_code "eng" :level_code "PERUS"}]})

(def organizers-json
  (j/read-value (slurp "test/resources/organizers.json")))

(def exam-sessions-json
  (j/read-value (slurp "test/resources/exam_sessions.json")))

(def logout-request
  (slurp "test/resources/logoutRequest.xml"))

(def exam-session
  (slurp "test/resources/exam_session.json"))

(def organization
  (slurp "test/resources/organization.json"))

(def payment-formdata-json
  (j/read-value (slurp "test/resources/payment_formdata.json")))

(defn change-entry
  [json-string key value]
  (j/write-value-as-string (assoc-in (j/read-value json-string) [key] value)))

(def payment-config {:paytrail-host "https://payment.paytrail.com/e2"
                     :yki-payment-uri "http://localhost:8080/yki/payment"
                     :amount "100.00"
                     :msg {:fi "msg_fi"
                           :sv "msg_sv"}})

(defn cas-mock-routes [port]
  {"/cas/v1/tickets" {:status 201
                      :method :post
                      :headers {"Location" (str "http://localhost:" port "/cas/v1/tickets/TGT-1-FFDFHDSJK")}
                      :body "ST-1-FFDFHDSJK2"}
   "/oppijanumerorekisteri-service/j_spring_cas_security_check" {:status 200
                                                                 :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}}
   "/cas/v1/tickets/TGT-1-FFDFHDSJK" {:status 200
                                      :method :post
                                      :body "ST-1-FFDFHDSJK2"}})

(defn body [response]
  (slurp (:body response) :encoding "UTF-8"))

(defn access-log []
  (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"}))

(defn db []
  (duct.database.sql/->Boundary @embedded-db/conn))

(defn auth [url-helper]
  (ig/init-key :yki.middleware.auth/with-authentication
               {:url-helper url-helper
                :db (db)
                :session-config {:key "ad7tbRZIG839gDo2"
                                 :cookie-attrs {:max-age 28800
                                                :http-only true
                                                :secure false
                                                :domain "localhost"
                                                :path "/yki"}}}))
(defn cas-client [url-helper]
  (ig/init-key  :yki.boundary.cas/cas-client {:url-helper url-helper
                                              :cas-creds {:username "username"
                                                          :password "password"}}))
(defn onr-client [url-helper]
  onr-client (ig/init-key :yki.boundary.onr/onr-client {:url-helper url-helper
                                                        :cas-client (cas-client url-helper)}))
(defn permissions-client [url-helper]
  (ig/init-key  :yki.boundary.permissions/permissions-client
                {:url-helper url-helper
                 :cas-client (cas-client url-helper)}))

(defn auth-handler
  [auth url-helper]
  (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                          :db (db)
                                                          :onr-client (onr-client url-helper)
                                                          :url-helper url-helper
                                                          :access-log (access-log)
                                                          :permissions-client (permissions-client url-helper)
                                                          :cas-client (cas-client url-helper)})))
(defn email-q []
  (ig/init-key :yki.job.job-queue/init {:db-config {:db embedded-db/db-spec}})
  (ig/init-key :yki.job.job-queue/email-q {}))

(defn data-sync-q  []
  (ig/init-key :yki.job.job-queue/init {:db-config {:db embedded-db/db-spec}})
  (ig/init-key :yki.job.job-queue/data-sync-q  {}))

(defn body-as-json [response]
  (j/read-value (body response)))

(def registration-form {:first_name  "Aku"
                        :last_name  "Ankka"
                        :gender "1"
                        :nationalities ["246"]
                        :birth_date "1999-01-01"
                        :certificate_lang "fi"
                        :exam_lang "fi"
                        :post_office "Ankkalinna"
                        :zip "12345"
                        :email "aa@al.fi"
                        :street_address "Katu 3"
                        :phone_number "+3584012345"})

(def registration-form-2 {:first_name  "Iines"
                          :last_name  "Ankka"
                          :gender nil
                          :nationalities ["246"]
                          :ssn "301079-122F"
                          :certificate_lang "fi"
                          :exam_lang "fi"
                          :post_office "Ankkalinna"
                          :zip "12346"
                          :email "aa@al.fi"
                          :street_address "Katu 4"
                          :phone_number "+3584012346"})

(defn insert-organizer [oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, extra)
        VALUES (" oid ", '2018-01-01', '2089-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi')")))

(defn insert-payment-config [oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO payment_config (organizer_id, merchant_id, merchant_secret)
        VALUES ((SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL), 12345, 'SECRET_KEY')")))

(defn insert-languages [oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('fin', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('swe', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))")))

(defn insert-exam-dates []
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2039-05-02', '2039-01-01', '2039-03-01')"))

(def select-participant "(SELECT id from participant WHERE external_user_id = 'test@user.com')")

(def select-exam-session "(SELECT id from exam_session WHERE max_participants = 5)")

(defn insert-exam-session [exam-date-id]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
        language_code,
        level_code,
        office_oid,
        exam_date_id,
        max_participants,
        published_at)
          VALUES (
            (SELECT id FROM organizer where oid = '1.2.3.4'),
            'fin', 'PERUS', '1.2.3.4.5'," exam-date-id ", 5, null)")))

(defn insert-base-data []
  (insert-organizer "'1.2.3.4'")
  (insert-payment-config "'1.2.3.4'")
  (insert-languages "'1.2.3.4'")
  (insert-exam-dates)
  (jdbc/execute! @embedded-db/conn "UPDATE exam_date set registration_end_date = '2039-12-01'")
  (insert-exam-session 1)

  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session_location (name,
    address,
    other_location_info,
    lang,
    exam_session_id)
      VALUES (
        'Omenia',
        'Upseerinkatu 11, Espoo',
        'Other info',
        'fi',
        (SELECT id FROM exam_session where max_participants = 5))"))

  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id, email) VALUES ('test@user.com', 'test@user.com') ")))

(defn insert-payment []
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO registration(state, exam_session_id, participant_id) values ('SUBMITTED', " select-exam-session ", " select-participant ")"))
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO payment(state, registration_id, amount, lang, order_number) values ('UNPAID', (SELECT id FROM registration where state = 'SUBMITTED'), 100.00, 'fi', 'order1234')")))

(defn insert-registrations [state]
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values
    ('5.4.3.2.2', '" state "', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form-2) "')"))
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values
                                    ('5.4.3.2.1','" state "', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form) "')")))

(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
          (code, type, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
            VALUES ('" (login-link/sha256-hash code) "', 'REGISTRATION', " select-participant ", " select-exam-session ", '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

(defn insert-cas-ticket []
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO cas_ticketstore (ticket) VALUES ('ST-15126') ON CONFLICT (ticket) DO NOTHING")))

(defn select [query]
  (jdbc/query @embedded-db/conn query))

(defn select-one [query]
  (first (select query)))

(defn get-exam-session-id []
  (:id (select-one "SELECT id from exam_session WHERE max_participants = 5")))

(defn login-with-login-link [session]
  (-> session
      (peridot/request (str routing/auth-root "/login?code=" code-ok))))

(defn create-url-helper [uri]
  (ig/init-key :yki.util/url-helper {:virkailija-host uri :oppija-host uri :yki-register-host uri :yki-host-virkailija uri :alb-host (str "http://" uri) :scheme "http"}))

(defn create-routes [port]
  (let [uri (str "localhost:" port)
        db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (create-url-helper uri)
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db db :data-sync-q  (data-sync-q)})
        file-store (ig/init-key :yki.boundary.files/liiteri-file-store {:url-helper url-helper})
        auth (ig/init-key :yki.middleware.no-auth/with-authentication {:url-helper url-helper
                                                                       :db db
                                                                       :session-config {:key "ad7tbRZIG839gDo2"
                                                                                        :cookie-attrs {:max-age 28800
                                                                                                       :http-only true
                                                                                                       :domain "localhost"
                                                                                                       :secure false
                                                                                                       :path "/yki"}}})
        auth-handler (auth-handler auth url-helper)
        file-handler (ig/init-key :yki.handler/file {:db db :file-store file-store})
        organizer-handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                                       :auth auth
                                                                                       :data-sync-q (data-sync-q)
                                                                                       :access-log (access-log)
                                                                                       :url-helper url-helper
                                                                                       :exam-session-handler exam-session-handler
                                                                                       :file-handler file-handler}))]
    (routes organizer-handler auth-handler)))

(defn send-request-with-tx
  ([request]
   (send-request-with-tx request 8080))
  ([request port]
   ((create-routes port) request)))
