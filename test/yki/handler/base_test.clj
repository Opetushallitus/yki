(ns yki.handler.base-test
  (:require [integrant.core :as ig]
            [duct.database.sql :as sql]
            [jsonista.core :as j]
            [muuntaja.middleware :as middleware]
            [compojure.core :refer :all]
            [peridot.core :as peridot]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.login-link :as login-link]
            [yki.handler.routing :as routing]
            [yki.handler.exam-session]
            [yki.handler.exam-date]
            [yki.handler.file]
            [yki.handler.auth]
            [yki.job.job-queue]
            [yki.middleware.no-auth]
            [yki.util.url-helper]
            [yki.util.common :as c]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [yki.handler.organizer]
            [clojure.string :as str]))

(def code-ok "4ce84260-3d04-445e-b914-38e93c1ef667")

(def organizer {:oid                  "1.2.3.4"
                :agreement_start_date "2018-01-01T00:00:00Z"
                :agreement_end_date   "2049-01-01T00:00:00Z"
                :contact_email        "fuu@bar.com"
                :contact_name         "fuu"
                :contact_phone_number "123456"
                :extra                "shared@oph.fi"
                :merchant             {:merchant_id 123456 :merchant_secret "SECRET"}
                :languages            [{:language_code "fin" :level_code "PERUS"}
                                       {:language_code "eng" :level_code "PERUS"}]})

(defn- read-json-from-file [path]
  (j/read-value (slurp path)))

(def exam-sessions-json
  (read-json-from-file "test/resources/exam_sessions.json"))

(def exam-session
  (slurp "test/resources/exam_session.json"))

(def exam-date
  (slurp "test/resources/exam_date.json"))

(def organization
  (slurp "test/resources/organization.json"))

(def payment-formdata-json
  (read-json-from-file "test/resources/payment_formdata.json"))

(def evaluation-payment-formdata-json
  (read-json-from-file "test/resources/evaluation_payment_formdata.json"))

(def post-admission
  (slurp "test/resources/post_admission.json"))

(defn days-ago [days]
  (f/unparse (f/formatter c/date-format) (t/minus (t/now) (t/days days))))

(defn days-from-now [days]
  (f/unparse (f/formatter c/date-format) (t/plus (t/now) (t/days days))))

(defn yesterday []
  (days-ago 1))

(defn two-weeks-ago []
  (days-ago 14))

(defn two-weeks-from-now []
  (days-from-now 14))

(defn change-entry
  [json-string key value]
  (j/write-value-as-string (assoc-in (j/read-value json-string) [key] value)))

(def payment-config {:paytrail-host   "https://payment.paytrail.com/e2"
                     :yki-payment-uri "http://localhost:8080/yki/payment"
                     :amount          {:PERUS "100.00"
                                       :KESKI "123.00"
                                       :YLIN  "160.00"}
                     :msg             {:fi "msg_fi"
                                       :sv "msg_sv"}})

(defn cas-mock-routes [port]
  {"/cas/v1/tickets"                                            {:status    201
                                                                 :method    :post
                                                                 :headers   {"Location" (str "http://localhost:" port "/cas/v1/tickets/TGT-1-FFDFHDSJK")}
                                                                 :caller-id {"Caller-Id" "1.2.246.562.10.00000000001.yki"}
                                                                 :body      "ST-1-FFDFHDSJK2"}
   "/oppijanumerorekisteri-service/j_spring_cas_security_check" {:status    200
                                                                 :headers   {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}
                                                                 :caller-id {"Caller-Id" "1.2.246.562.10.00000000001.yki"}}
   "/cas/v1/tickets/TGT-1-FFDFHDSJK"                            {:status 200
                                                                 :method :post
                                                                 :body   "ST-1-FFDFHDSJK2"}})

(defn body [response]
  (slurp (:body response) :encoding "UTF-8"))

(defn access-log []
  (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"}))

(defn db []
  (sql/->Boundary @embedded-db/conn))

(defn auth [url-helper]
  (ig/init-key :yki.middleware.auth/with-authentication
               {:url-helper     url-helper
                :db             (db)
                :session-config {:key          "ad7tbRZIG839gDo2"
                                 :cookie-attrs {:max-age   28800
                                                :http-only true
                                                :secure    false
                                                :domain    "localhost"
                                                :path      "/yki"}}}))
(defn cas-client [url-helper]
  (ig/init-key :yki.boundary.cas/cas-client {:url-helper url-helper
                                             :cas-creds  {:username "username"
                                                          :password "password"}}))
(defn onr-client [url-helper]
  onr-client (ig/init-key :yki.boundary.onr/onr-client {:url-helper url-helper
                                                        :cas-client (cas-client url-helper)}))
(defn permissions-client [url-helper]
  (ig/init-key :yki.boundary.permissions/permissions-client
               {:url-helper url-helper
                :cas-client (cas-client url-helper)}))

(defn auth-handler
  [auth url-helper]
  (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth               auth
                                                          :db                 (db)
                                                          :onr-client         (onr-client url-helper)
                                                          :url-helper         url-helper
                                                          :access-log         (access-log)
                                                          :permissions-client (permissions-client url-helper)
                                                          :cas-client         (cas-client url-helper)})))
(defn email-q []
  (ig/init-key :yki.job.job-queue/init {:db-config {:db (embedded-db/db-spec)}})
  (ig/init-key :yki.job.job-queue/email-q {}))

(defn data-sync-q []
  (ig/init-key :yki.job.job-queue/init {:db-config {:db (embedded-db/db-spec)}})
  (ig/init-key :yki.job.job-queue/data-sync-q {}))

(defn body-as-json [response]
  (j/read-value (body response)))

(def registration-form {:first_name       "Aku"
                        :last_name        "Ankka"
                        :gender           "1"
                        :nationalities    ["180"]
                        :birthdate        "1999-01-01"
                        :ssn              "201190-9012"
                        :certificate_lang "fi"
                        :exam_lang        "fi"
                        :post_office      "Ankkalinna"
                        :zip              "12345"
                        :email            "aa@al.fi"
                        :street_address   "Katu 3"
                        :phone_number     "+3584012345"})

(def registration-form-2 {:first_name       "Iines"
                          :last_name        "Ankka"
                          :gender           nil
                          :nationalities    ["246"]
                          :ssn              "301079-900U"
                          :certificate_lang "fi"
                          :exam_lang        "fi"
                          :post_office      "Ankkalinna"
                          :zip              "12346"
                          :email            "aa@al.fi"
                          :street_address   "Katu 4"
                          :phone_number     "+3584012346"})

(def post-admission-registration-form {:first_name       "Roope"
                                       :last_name        "Ankka"
                                       :gender           nil
                                       :nationalities    ["246"]
                                       :ssn              "301079-083N"
                                       :certificate_lang "fi"
                                       :exam_lang        "fi"
                                       :post_office      "Ankkalinna"
                                       :zip              "12346"
                                       :email            "roope@al.fi"
                                       :street_address   "Katu 5"
                                       :phone_number     "+3584012347"})
(defn select [query]
  (jdbc/query @embedded-db/conn query))

(defn select-one [query]
  (first (select query)))

(defn insert-organizer [organizer-oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, extra)
        VALUES ('" organizer-oid "', '2018-01-01', '2089-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi') ON CONFLICT DO NOTHING")))

(defn insert-payment-config [organizer-oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO payment_config (organizer_id, merchant_id, merchant_secret)
        VALUES ((SELECT id FROM organizer WHERE oid = '" organizer-oid "' AND deleted_at IS NULL), 12345, 'SECRET_KEY')")))

(defn insert-languages [organizer-oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('fin', 'PERUS', (SELECT id FROM organizer WHERE oid = '" organizer-oid "' AND deleted_at IS NULL))"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('swe', 'PERUS', (SELECT id FROM organizer WHERE oid = '" organizer-oid "' AND deleted_at IS NULL))")))

(defn insert-attachment-metadata [organizer-oid external-id]
  (jdbc/execute! @embedded-db/conn
                 (str "INSERT INTO attachment_metadata (external_id, organizer_id) values ('"
                      external-id
                      "', (SELECT id FROM organizer WHERE oid = '"
                      organizer-oid
                      "' AND deleted_at IS NULL))")))

(defn insert-exam-dates []
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2039-05-02', '2039-01-01', '2039-03-01')")
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date, post_admission_end_date) VALUES ('2039-05-10', '2039-01-01', '2039-03-01', '2039-04-15')"))

(defn insert-post-admission-dates []
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date, post_admission_start_date, post_admission_end_date)
                                    VALUES ('2041-06-01', '2041-01-01', '2041-01-30', '2041-03-01', '2041-03-30')")
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date, post_admission_start_date, post_admission_end_date, post_admission_enabled)
                                    VALUES ('2041-07-01', '2041-01-01', '2041-01-30', '2041-03-01', '2041-03-30', true)"))

(defn insert-custom-exam-date [exam-date reg-start reg-end]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('" exam-date "', '" reg-start "', '" reg-end "')")))

(def select-participant "(SELECT id from participant WHERE external_user_id = 'test@user.com')")

(def select-exam-session "(SELECT id from exam_session WHERE max_participants = 5)")

(defn select-exam-date [id]
  (str "(SELECT * from exam_date WHERE id=" id ")"))

(defn select-exam-date-id-by-date [exam-date]
  (str "(SELECT id from exam_date WHERE exam_date='" exam-date "')"))

(defn select-exam-session-by-date [exam-date]
  (str "(SELECT * from exam_session WHERE exam_date_id=" (select-exam-date-id-by-date exam-date) ")"))

(defn select-exam-date-languages-by-date-id [exam-date-id]
  (str "(SELECT id, language_code, level_code from exam_date_language WHERE exam_date_id='" exam-date-id "' AND deleted_at IS NULL)"))

(defn select-evaluation-by-date [exam-date]
  (select-one (str "(SELECT * from evaluation WHERE exam_date_id=" (select-exam-date-id-by-date exam-date) ")")))

(defn insert-exam-session
  [exam-date-id organizer-oid count]
  (let [office-oid (str organizer-oid ".5")]
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
          language_code,
          level_code,
          office_oid,
          exam_date_id,
          max_participants,
          published_at)
            VALUES (
              (SELECT id FROM organizer where oid = '" organizer-oid "'),
              'fin', 'PERUS', '" office-oid "'," exam-date-id ", " count ", null)"))))

(defn insert-exam-session-with-post-admission
  [exam-date-id organizer-oid count quota]
  (let [office-oid (str organizer-oid ".5")]
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
          language_code,
          level_code,
          office_oid,
          exam_date_id,
          max_participants,
          published_at,
          post_admission_quota,
          post_admission_active)
            VALUES (
              (SELECT id FROM organizer where oid = '" organizer-oid "'),
              'fin', 'PERUS', '" office-oid "'," exam-date-id ", " count ", null, " quota ", true)"))))

(defn insert-exam-session-location
  [organizer-oid lang]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session_location (name,
    street_address,
    post_office,
    zip,
    other_location_info,
    lang,
    exam_session_id)
      VALUES (
        'Omenia',
        'Upseerinkatu 11',
        'Espoo',
        '00240',
        'Other info',
        '" lang "',
        (SELECT id FROM exam_session where organizer_id =  (SELECT id FROM organizer where oid = '" organizer-oid "') ))")))

(defn insert-exam-session-location-by-date
  [exam-date lang]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session_location (name,
    street_address,
    post_office,
    zip,
    other_location_info,
    lang,
    exam_session_id)
      VALUES (
        'Omenia',
        'Upseerinkatu 11',
        'Espoo',
        '00240',
        'Other info',
        '" lang "',
        (SELECT id FROM exam_session where exam_date_id =(SELECT id from exam_date WHERE exam_date='" exam-date "')))")))

(defn insert-evaluation-data []
  (jdbc/execute! @embedded-db/conn
                 "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-05-02', '2019-01-01', '2019-03-01')")
  (jdbc/execute! @embedded-db/conn
                 (str "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('" (two-weeks-ago) "', '" (days-ago 30) "', '" (days-ago 25) "')"))
  (let [exam-date-id          (fn [str-date] (:id (select-one (select-exam-date-id-by-date str-date))))
        exam-date-language-id (fn [id] (:id (select-one (select-exam-date-languages-by-date-id id))))
        first-date-id         (exam-date-id "2039-05-02")
        second-date-id        (exam-date-id "2039-05-10")
        third-date-id         (exam-date-id (two-weeks-ago))
        fourth-date-id        (exam-date-id "2019-05-02")]
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO exam_date_language(exam_date_id, language_code, level_code) VALUES (" first-date-id ", 'fin', 'PERUS')"))
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO exam_date_language(exam_date_id, language_code, level_code) VALUES (" second-date-id ", 'fin', 'PERUS')"))
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO exam_date_language(exam_date_id, language_code, level_code) VALUES (" third-date-id ", 'fin', 'PERUS')"))
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO exam_date_language(exam_date_id, language_code, level_code) VALUES (" fourth-date-id ", 'fin', 'PERUS')"))

    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation (exam_date_id, evaluation_start_date, evaluation_end_date, exam_date_language_id)
                                    VALUES (" first-date-id ", '2041-08-01', '2041-08-15', " (exam-date-language-id first-date-id) ")"))
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation (exam_date_id, evaluation_start_date, evaluation_end_date, exam_date_language_id)
                                    VALUES (" second-date-id ", '2041-08-01', '2041-08-15', " (exam-date-language-id second-date-id) ")"))
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation (exam_date_id, evaluation_start_date, evaluation_end_date, exam_date_language_id)
                                    VALUES (" third-date-id ", '" (yesterday) "', '" (two-weeks-from-now) "', " (exam-date-language-id third-date-id) ")"))
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation (exam_date_id, evaluation_start_date, evaluation_end_date, exam_date_language_id)
                                    VALUES (" fourth-date-id ", '2019-08-01', '2019-08-15', " (exam-date-language-id fourth-date-id) ")"))))

(defn insert-evaluation-payment-data [{:keys [first_names last_name email birthdate]}]
  (jdbc/execute! @embedded-db/conn (str "UPDATE evaluation_payment_config SET merchant_id=12345, merchant_secret='6pKF4jkv97zmqBJ3ZL8gUw5DfT2NMQ', email='kirjaamo@testi.fi' "))

  (let [evaluation-id (:id (select-evaluation-by-date (two-weeks-ago)))]
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order (evaluation_id, first_names, last_name, email, birthdate)
                                    VALUES (" evaluation-id ", '" first_names "', '" last_name "', '" email "', '" birthdate "')"))
    (let [evaluation-order-id (:id (select-one (str "SELECT id FROM evaluation_order WHERE email = '" email "'")))]
      (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order_subtest (evaluation_order_id, subtest)
                                   VALUES (" evaluation-order-id ", 'READING')"))
      (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order_subtest (evaluation_order_id, subtest)
                               VALUES (" evaluation-order-id ", 'LISTENING')"))
      (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_payment
                                               (state, evaluation_order_id, amount, lang, order_number) VALUES
                                               ('UNPAID'," evaluation-order-id ", 100, 'fi', 'YKI-EVA-TEST')")))))

(defn get-evaluation-order-and-status [{:keys [first_names last_name email birthdate]}]
  (select-one (str "SELECT
  eo.id,
  eo.first_names,
  eo.last_name,
  eo.email,
  eo.birthdate,
  edl.language_code,
  edl.level_code,
  ed.exam_date,
  ep.state,
  (
    SELECT array_to_json(array_agg(subtest))
    FROM (
      SELECT subtest
      FROM evaluation_order_subtest
      WHERE evaluation_order_id= eo.id
    ) subtest
  ) AS subtests
FROM evaluation_order eo
INNER JOIN evaluation ev ON eo.evaluation_id = ev.id
INNER JOIN exam_date_language edl ON edl.id = ev.exam_date_language_id
INNER JOIN exam_date ed ON ev.exam_date_id = ed.id
INNER JOIN evaluation_payment ep ON ep.evaluation_order_id = eo.id
WHERE eo.first_names = '" first_names "' AND eo.last_name = '" last_name "' AND eo.email = '" email "' AND eo.birthdate = '" birthdate "'")))

(defn get-evaluation-payment-status-by-order-id [order-id]
  (select-one
    (str "SELECT ep.state, ep.amount
         FROM evaluation_order eo
         INNER JOIN evaluation_payment ep ON ep.evaluation_order_id = eo.id
         WHERE eo.id = " order-id " AND deleted_at IS NULL")))

(defn insert-base-data []
  (let [organizer-oid (:oid organizer)]
    (insert-organizer organizer-oid)
    (insert-payment-config organizer-oid)
    (insert-languages organizer-oid)
    (insert-exam-dates)
    (jdbc/execute! @embedded-db/conn "UPDATE exam_date set registration_end_date = '2039-12-01'")
    (insert-exam-session 1 organizer-oid 5)
    (insert-exam-session-location organizer-oid "fi")
    (insert-exam-session-location organizer-oid "sv")
    (insert-exam-session-location organizer-oid "en"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id, email) VALUES ('test@user.com', 'test@user.com') "))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id, email) VALUES ('anothertest@user.com', 'anothertest@user.com') "))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant (external_user_id, email) VALUES ('thirdtest@user.com', 'thirdtest@user.com') ")))

(defn insert-payment []
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(state, exam_session_id, participant_id) values ('SUBMITTED', " select-exam-session ", " select-participant ")"))
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO payment(state, registration_id, amount, lang, order_number) values ('UNPAID', (SELECT id FROM registration where state = 'SUBMITTED'), 100.00, 'fi', 'order1234')")))

(defn insert-exam-payment-new [registration-id amount reference transaction-id href]
  (let [quote-strings #(if (string? %)
                         (str "'" % "'")
                         (str %))
        values-str    (->> ["UNPAID" registration-id amount reference transaction-id href]
                           (map quote-strings)
                           (str/join ","))]
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO exam_payment_new(state, registration_id, amount, reference, transaction_id, href)
                 VALUES (" values-str ");"))))

(defn insert-registrations [state]
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values
     ('5.4.3.2.2', '" state "', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form-2) "')"))
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values
                                     ('5.4.3.2.1','" state "', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form) "')"))
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form, kind) values
                                     ('5.4.3.2.4','" state "', " select-exam-session ", " select-participant ",'" (j/write-value-as-string post-admission-registration-form) "', 'POST_ADMISSION')")))

(defn insert-unpaid-expired-registration []
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values
                                     ('5.4.3.2.3', 'EXPIRED', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form-2) "')"))
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO payment(state, registration_id, amount, lang, order_number) values
                         ('UNPAID', (SELECT id FROM registration where person_oid = '5.4.3.2.3'), 100.00, 'fi', 'order1234')")))

(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
          (code, type, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
            VALUES ('" (login-link/sha256-hash code) "', 'REGISTRATION', " select-participant ", " select-exam-session ", '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

(defn insert-cas-ticket []
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO cas_ticketstore (ticket) VALUES ('ST-15126') ON CONFLICT (ticket) DO NOTHING")))

(defn get-exam-session-id []
  (:id (select-one "SELECT id from exam_session WHERE max_participants = 5")))

(defn insert-post-admission-registration
  [organizer-oid count quota]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date, post_admission_start_date, post_admission_end_date) VALUES ('" (two-weeks-from-now) "', '2019-08-01', '2019-10-01','" (two-weeks-ago) "', '" (two-weeks-from-now) "')"))
  (let [exam-date-id        (:id (select-one (select-exam-date-id-by-date (two-weeks-from-now))))
        office-oid          (str organizer-oid ".5")
        insert-exam         (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_session (organizer_id,
          language_code,
          level_code,
          office_oid,
          exam_date_id,
          max_participants,
          published_at,
          post_admission_quota,
          post_admission_active)
            VALUES (
              (SELECT id FROM organizer where oid = '" organizer-oid "'),'fin', 'PERUS', '" office-oid "', " exam-date-id ", " count ", null, " quota ", true)"))
        exam-session-id     (:id (select-one (str "SELECT id FROM exam_session where exam_date_id = " exam-date-id ";")))
        user-id             (:id (select-one (str "SELECT id from participant WHERE external_user_id = 'thirdtest@user.com';")))
        insert-registration (jdbc/execute! @embedded-db/conn (str "INSERT INTO registration(person_oid, state, exam_session_id, participant_id, form) values ('5.4.3.2.3','COMPLETED', " exam-session-id ", " user-id ",'" (j/write-value-as-string post-admission-registration-form) "')"))]
    (doall insert-exam)
    (doall insert-registration)))

(defn login-with-login-link [session]
  (-> session
      (peridot/request (str routing/auth-root "/login?code=" code-ok))))

(defn create-url-helper [uri]
  (ig/init-key :yki.util/url-helper {:virkailija-host uri :oppija-host uri :yki-register-host uri :yki-host-virkailija uri :alb-host (str "http://" uri) :scheme "http" :oppija-sub-domain "yki."}))

(defn create-payment-helper [db url-helper use-new-payments-api?]
  (ig/init-key
    :yki.util/payment-helper
    {:db             db
     :url-helper     url-helper
     :payment-config {:use-new-payments-api? use-new-payments-api?
                      :amount                (:amount payment-config)}}))

(defn create-routes [port]
  (let [uri                  (str "localhost:" port)
        db                   (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper           (create-url-helper uri)
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db          db
                                                                     :url-helper  url-helper
                                                                     :email-q     (email-q)
                                                                     :data-sync-q (data-sync-q)})

        exam-date-handler    (ig/init-key :yki.handler/exam-date {:db db})

        file-store           (ig/init-key :yki.boundary.files/liiteri-file-store {:url-helper url-helper})
        auth                 (ig/init-key :yki.middleware.no-auth/with-authentication {:url-helper     url-helper
                                                                                       :db             db
                                                                                       :session-config {:key          "ad7tbRZIG839gDo2"
                                                                                                        :cookie-attrs {:max-age   28800
                                                                                                                       :http-only true
                                                                                                                       :domain    "localhost"
                                                                                                                       :secure    false
                                                                                                                       :path      "/yki"}}})
        auth-handler         (auth-handler auth url-helper)
        file-handler         (ig/init-key :yki.handler/file {:db db :file-store file-store})
        organizer-handler    (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db                   db
                                                                                          :auth                 auth
                                                                                          :data-sync-q          (data-sync-q)
                                                                                          :access-log           (access-log)
                                                                                          :url-helper           url-helper
                                                                                          :exam-session-handler exam-session-handler
                                                                                          :exam-date-handler    exam-date-handler
                                                                                          :file-handler         file-handler}))]
    (routes organizer-handler auth-handler)))

(defn send-request-with-tx
  ([request]
   (send-request-with-tx request 8080))
  ([request port]
   ((create-routes port) request)))
