(ns yki.handler.base-test
  (:require
    [clj-time.format :as f]
    [clj-time.core :as t]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as str]
    [compojure.core :refer [routes]]
    [duct.database.sql :as sql]
    [integrant.core :as ig]
    [jsonista.core :as j]
    [muuntaja.middleware :as middleware]
    [peridot.core :as peridot]
    [yki.boundary.cas]
    [yki.embedded-db :as embedded-db]
    [yki.env]
    [yki.handler.auth]
    [yki.handler.exam-date]
    [yki.handler.exam-session]
    [yki.handler.organizer]
    [yki.handler.login-link :as login-link]
    [yki.handler.quarantine]
    [yki.handler.routing :as routing]
    [yki.handler.user]
    [yki.job.job-queue]
    [yki.middleware.no-auth]
    [yki.util.common :as c]
    [yki.util.pdf :refer [PdfTemplateRenderer]]
    [yki.util.template-util :as template-util]
    [yki.util.url-helper]))

(def code-ok "4ce84260-3d04-445e-b914-38e93c1ef667")

(def organizer {:oid                  "1.2.3.4"
                :agreement_start_date "2018-01-01T00:00:00Z"
                :agreement_end_date   "2049-01-01T00:00:00Z"
                :contact_email        "fuu@bar.com"
                :contact_name         "fuu"
                :contact_phone_number "123456"
                :extra                "shared@oph.fi"
                :languages            [{:language_code "fin" :level_code "PERUS"}
                                       {:language_code "eng" :level_code "PERUS"}]})

(defn- read-json-from-file [path]
  (j/read-value (slurp path)))

(def exam-sessions-json
  (read-json-from-file "test/resources/exam_sessions.json"))

(def exam-session
  (slurp "test/resources/exam_session.json"))

(def organization
  (slurp "test/resources/organization.json"))

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

(defn environment [env]
  (ig/init-key :yki.env/environment {:environment env}))

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
  (ig/init-key :yki.boundary.onr/onr-client {:url-helper url-helper
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
(defn user-handler
  [auth env]
  (middleware/wrap-format (ig/init-key :yki.handler/user {:auth       auth
                                                          :db         (db)
                                                          :access-log (access-log)
                                                          :environment env})))
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
                        :ssn              "010199-9012"
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

(def quarantine-form {:language_code "fin"
                      :start_date    "2040-06-29"
                      :end_date      "2040-12-30"
                      :birthdate     "1999-01-27"
                      :ssn           "270199-900U"
                      :first_name    "Max"
                      :last_name     "Syöttöpaine"
                      :email         "email@invalid.invalid"
                      :phone_number  "0401234567"
                      :diary_number  "OPH-1234-2023"})

(defn select [query]
  (jdbc/query @embedded-db/conn query))

(defn select-one [query]
  (first (select query)))

(defn execute! [statement]
  (jdbc/execute! @embedded-db/conn statement))

(defn insert-quarantine [quarantine]
  (let [columns     [:language_code :start_date :end_date :birthdate :ssn :first_name :last_name :email :phone_number :diary_number]
        columns-str (->> columns
                         (map name)
                         (str/join ","))
        values-str  (->> columns
                         (map quarantine)
                         (map #(if % (str "'" % "'") "NULL"))
                         (str/join ","))]
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO quarantine (" columns-str ") VALUES (" values-str ") ON CONFLICT DO NOTHING;"))))

(defn update-quarantine-language! [id language]
  (jdbc/execute! @embedded-db/conn (str "UPDATE quarantine SET updated=(current_timestamp + (5 * interval '1 seconds')), language_code='" language "' WHERE id=" id ";")))

(defn update-exam-date! [exam-date-id new-exam-date]
  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET exam_date='" new-exam-date "' WHERE id=" exam-date-id ";")))

(defn update-registration-form! [registration-id attr val]
  {:pre [(pos-int? registration-id) (string? attr) (or (string? val)
                                                       (nil? val))]}
  (jdbc/execute! @embedded-db/conn (str "UPDATE registration SET form = JSONB_SET(form, '{" attr "}', '" (if val (str "\"" val "\"") "null") "') WHERE id=" registration-id ";")))

(defn update-registration-state! [registration-id state]
  (jdbc/execute! @embedded-db/conn (str "UPDATE registration SET state='" state "' WHERE id=" registration-id ";")))

(defn insert-organizer [organizer-oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, extra)
        VALUES ('" organizer-oid "', '2018-01-01', '2089-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi') ON CONFLICT DO NOTHING")))

(defn insert-languages [organizer-oid]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('fin', 'PERUS', (SELECT id FROM organizer WHERE oid = '" organizer-oid "' AND deleted_at IS NULL))"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO exam_language (language_code, level_code, organizer_id) values ('swe', 'PERUS', (SELECT id FROM organizer WHERE oid = '" organizer-oid "' AND deleted_at IS NULL))")))

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

(defn insert-evaluation-order-data [{:keys [first_names last_name email birthdate]}]
  (let [evaluation-id (:id (select-evaluation-by-date (two-weeks-ago)))]
    (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order (evaluation_id, first_names, last_name, email, birthdate)
                                    VALUES (" evaluation-id ", '" first_names "', '" last_name "', '" email "', '" birthdate "')"))
    (let [evaluation-order-id (:id (select-one (str "SELECT id FROM evaluation_order WHERE email = '" email "'")))]
      (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order_subtest (evaluation_order_id, subtest)
                                   VALUES (" evaluation-order-id ", 'READING')"))
      (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_order_subtest (evaluation_order_id, subtest)
                               VALUES (" evaluation-order-id ", 'LISTENING')"))
      {:evaluation-order-id evaluation-order-id})))

(defn insert-evaluation-payment-new-data [evaluation-order-id]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO evaluation_payment_new
                                               (state, evaluation_order_id, amount, reference, transaction_id, href) VALUES
                                               ('UNPAID'," evaluation-order-id ", 100, 'fake-reference', 'fake-transaction-id',
                                               'https://pay.paytrail.com/pay/fake-transaction-id')")))

(defn insert-base-data []
  (let [organizer-oid (:oid organizer)]
    (insert-organizer organizer-oid)
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
                                     ('5.4.3.2.3', 'EXPIRED', " select-exam-session ", " select-participant ",'" (j/write-value-as-string registration-form-2) "')")))

(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
          (code, type, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
            VALUES ('" (login-link/sha256-hash code) "', 'REGISTRATION', " select-participant ", " select-exam-session ", '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

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
  (let [uri-with-schema (str "http://" uri)]
    (ig/init-key
      :yki.util/url-helper
      {:virkailija-host           uri
       :oppija-host               uri
       :yki-register-host         uri
       :yki-host-virkailija       uri
       :alb-host                  uri-with-schema
       :scheme                    "http"
       :oppija-sub-domain         "yki."
       :cas-service-base          uri-with-schema
       :cas-oppija-service-base   uri
       :kayttooikeus-service-base uri-with-schema
       :koodisto-service-base     uri-with-schema
       :onr-service-base          uri-with-schema
       :organisaatio-service-base uri-with-schema})))

(defn mock-pdf-renderer []
  (reify PdfTemplateRenderer
    (template+data->pdf-bytes [_ template-name language template-data]
      (template-util/render template-name language template-data))))

(defn create-examination-payment-helper [db url-helper]
  (ig/init-key
    :yki.util/exam-payment-helper
    {:db             db
     :url-helper     url-helper
     :payment-config {:amount          {:PERUS 135
                                        :KESKI 155
                                        :YLIN  195}
                      :merchant-id     "375917"
                      :merchant-secret "SAIPPUAKAUPPIAS"}
     :pdf-renderer   (mock-pdf-renderer)}))

(def new-evaluation-payment-config {:amount          {:READING   50
                                                      :LISTENING 50
                                                      :WRITING   50
                                                      :SPEAKING  50}
                                    :merchant-id     "375917"
                                    :merchant-secret "SAIPPUAKAUPPIAS"})

(defn create-evaluation-payment-helper [db url-helper]
  (ig/init-key
    :yki.util/evaluation-payment-helper
    {:db             db
     :url-helper     url-helper
     :payment-config new-evaluation-payment-config}))

(defn no-auth-middleware [db url-helper]
  (ig/init-key
    :yki.middleware.no-auth/with-authentication
    {:url-helper     url-helper
     :db             db
     :session-config {:key          "ad7tbRZIG839gDo2"
                      :cookie-attrs {:max-age   28800
                                     :http-only true
                                     :domain    "localhost"
                                     :secure    false
                                     :path      "/yki"}}}))

(defn no-auth-fake-session-oid-middleware [oid]
  (ig/init-key :yki.middleware.no-auth/with-fake-oid {:oid oid}))

(defn create-routes [port]
  (let [uri                  (str "localhost:" port)
        db                   (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper           (create-url-helper uri)
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db           db
                                                                     :url-helper   url-helper
                                                                     :email-q      (email-q)
                                                                     :pdf-renderer (mock-pdf-renderer)
                                                                     :data-sync-q  (data-sync-q)})

        exam-date-handler    (ig/init-key :yki.handler/exam-date {:db db})

        auth                 (no-auth-middleware db url-helper)
        auth-handler         (auth-handler auth url-helper)
        quarantine-handler   (middleware/wrap-format (ig/init-key :yki.handler/quarantine {:access-log (access-log)
                                                                                           :auth       auth
                                                                                           :db         db
                                                                                           :url-helper url-helper}))
        organizer-handler    (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db                   db
                                                                                          :auth                 auth
                                                                                          :data-sync-q          (data-sync-q)
                                                                                          :access-log           (access-log)
                                                                                          :url-helper           url-helper
                                                                                          :exam-session-handler exam-session-handler
                                                                                          :exam-date-handler    exam-date-handler}))]
    (routes organizer-handler quarantine-handler auth-handler)))

(defn send-request-with-tx
  ([request]
   (send-request-with-tx request 8080))
  ([request port]
   ((create-routes port) request)))
