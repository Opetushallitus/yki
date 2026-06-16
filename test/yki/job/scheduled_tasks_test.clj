(ns yki.job.scheduled-tasks-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.java.jdbc :as jdbc]
    [clj-time.core :as t]
    [integrant.core :as ig]
    [jsonista.core :as j]
    [pgqueue.core :as pgq]
    [stub-http.core :refer [with-routes!]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.job.scheduled-tasks]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def email-req
  {:recipients ["test@test.com"]
   :created    (System/currentTimeMillis)
   :subject    "subject"
   :body       "body"})

(defn create-email-q-reader
  [port retry-duration-in-days]
  (ig/init-key :yki.job.scheduled-tasks/email-queue-reader {:url-helper             (base/create-url-helper (str "localhost:" port))
                                                            :handle-at-once-at-most 1
                                                            :basic-auth             {:user "user" :password "pass"}
                                                            :retry-duration-in-days retry-duration-in-days
                                                            :email-q                (base/email-q)}))

(deftest handle-email-request-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall"                              {:status 200 :content-type "application/json"
                                                                             :body   (j/write-value-as-string {:id 1})}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
    (let [email-q (base/email-q)
          _       (pgq/put email-q email-req)
          reader  (create-email-q-reader port 1)]
      (testing "should read email request from queue and send email"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (count (:recordings (first @(:routes server)))) 1))
        (is (= (pgq/count email-q) 0))))))

(deftest handle-started-registration-expired-test
  (base/insert-base-data)
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(state, exam_session_id, participant_id, started_at) values
                                     ('STARTED'," base/select-exam-session "," base/select-participant ", (current_timestamp - interval '61 minutes'))"))

  (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-state-handler {:db (base/db)})
        _                          (registration-state-handler)
        registration               (base/select-one "SELECT * FROM registration")]
    (testing "should set state of registration started over 1 hour ago to expired"
      (is (= (:state registration) "EXPIRED")))))

(deftest handle-started-registration-not-yet-expired-test
  (base/insert-base-data)
  (jdbc/execute! @embedded-db/conn (str
                                     "INSERT INTO registration(state, exam_session_id, participant_id, started_at) values
                                     ('STARTED'," base/select-exam-session "," base/select-participant ", (current_timestamp - interval '29 minutes'))"))

  (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-state-handler {:db (base/db)})
        _                          (registration-state-handler)
        registration               (base/select-one "SELECT * FROM registration")]
    (testing "should leave registration state at STARTED when run just before the 30 min limit"
      (is (= (:state registration) "STARTED")))))

(deftest handle-submitted-registration-expired-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (let [[reg-one-id reg-two-id] (map :id (base/select "SELECT id FROM registration where state = 'SUBMITTED'"))]
    (jdbc/execute! @embedded-db/conn
                   (str "UPDATE registration SET expires_at = (current_timestamp - interval '1 mins') WHERE id=" reg-one-id))
    (jdbc/execute! @embedded-db/conn
                   (str "UPDATE registration SET expires_at = (current_timestamp + interval '1 mins') WHERE id=" reg-two-id))
    (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-state-handler {:db (base/db)})
          _                          (registration-state-handler)
          registration-1             (base/select-one (str "SELECT * FROM registration WHERE id=" reg-one-id ";"))
          registration-2             (base/select-one (str "SELECT * FROM registration WHERE id=" reg-two-id ";"))]
      (testing "registrations that have passed their time of expiry will be expired"
        (is (= (:state registration-1) "EXPIRED"))
        (is (= (:state registration-2) "SUBMITTED"))))))

(deftest handle-exam-session-create-request-test
  (base/insert-base-data)
  (with-routes!
    (merge base/onr-mock-routes
           {"/organisaatio-service/rest/organisaatio/v4/1.2.3.4"   {:status       200
                                                                    :content-type "application/json"
                                                                    :body         (slurp "test/resources/organization.json")}
            "/organisaatio-service/rest/organisaatio/v4/1.2.3.4.5" {:status       200
                                                                    :content-type "application/json"
                                                                    :body         (slurp "test/resources/organization.json")}
            "/oph/tutkinto"                                        {:status       201
                                                                    :content-type "application/json"
                                                                    :body         "{}"}
            "/oph/tutkintotilaisuus"                               {:status       201
                                                                    :content-type "application/json"
                                                                    :body         "{}"}
            "/oph/jarjestaja"                                      {:status       201
                                                                    :content-type "application/json"
                                                                    :body         "{}"}})
    (let [data-sync-q     (base/data-sync-q)
          db              (base/db)
          exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
          es              (exam-session-db/get-exam-session-by-id db exam-session-id)
          _               (pgq/put data-sync-q {:exam-session es
                                                :type         "CREATE"
                                                :created      (System/currentTimeMillis)})
          reader          (ig/init-key :yki.job.scheduled-tasks/data-sync-queue-reader {:url-helper             (base/create-url-helper (str "localhost:" port))
                                                                                        :db                     db
                                                                                        :disabled               false
                                                                                        :basic-auth             {:user "user" :password "pass"}
                                                                                        :retry-duration-in-days 1
                                                                                        :data-sync-q            data-sync-q})]
      (testing "should read email request from queue and send email"
        (is (= (pgq/count data-sync-q) 1))
        (reader)
        (is (= (pgq/count data-sync-q) 0))))))

(deftest queue-reader-retry-if-execution-fails-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 500 :content-type "application/json"
                                                :body   (j/write-value-as-string {:error "fail"})}}
    (let [email-q (base/email-q)
          _       (pgq/put email-q email-req)
          reader  (create-email-q-reader port 1)]
      (testing "should return request to queue if execution fails"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (pgq/count email-q) 1))))))

(deftest queue-reader-no-retry-when-retry-duration-reached-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 500 :content-type "application/json"
                                                :body   (j/write-value-as-string {:error "fail"})}}
    (let [email-q (base/email-q)
          _       (pgq/put email-q email-req)
          reader  (create-email-q-reader port 0)]
      (testing "should remove message from queue"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (pgq/count email-q) 0))))))

(deftest handle-exam-session-participants-sync-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (jdbc/execute! @embedded-db/conn "UPDATE exam_session SET last_sync_at = NOW()")
  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set exam_date = '" (base/two-weeks-from-now) "'"))
  (with-routes!
    {"/oph/osallistujat"                                                    {:status 200
                                                                             :body   "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
    (let [url-helper  (base/create-url-helper (str "localhost:" port))
          handler     (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db                     (base/db)
                                                                                       :disabled               false
                                                                                       :basic-auth             {:user "user" :password "pass"}
                                                                                       :retry-duration-in-days 14
                                                                                       :url-helper             url-helper
                                                                                       :onr-client             (base/onr-client url-helper)})
          _           (handler)
          sync_status (base/select-one "SELECT * FROM participant_sync_status")]
      (testing "should send participants to yki register and set sync status to success"
        (is (= (count (:recordings (first @(:routes server)))) 1))
        (is (some? (:success_at sync_status))))
      (testing "should send participants only once"
        (handler)
        (is (= (count (:recordings (first @(:routes server)))) 1))))))

(deftest handle-exam-session-participants-failure-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (jdbc/execute! @embedded-db/conn "UPDATE exam_session SET last_sync_at = NOW()")
  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set registration_end_date = '" (base/two-weeks-ago) "'"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant_sync_status (exam_session_id, failed_at) VALUES (1, '" (base/yesterday) "')"))

  (with-routes!
    {"/oph/osallistujat"                                                    {:status 500
                                                                             :body   "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
    (let [url-helper       (base/create-url-helper (str "localhost:" port))
          handler          (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db                     (base/db)
                                                                                            :disabled               false
                                                                                            :basic-auth             {:user "user" :password "pass"}
                                                                                            :retry-duration-in-days 14
                                                                                            :url-helper             url-helper
                                                                                            :onr-client             (base/onr-client url-helper)})
          failed_at_before (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))
          _                (handler)
          failed_at_after  (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))]
      (testing "should update failed at timestamp"
        (is (t/after? failed_at_after failed_at_before))))))

(defn- insert-change-event! [{:keys [event exam_session_id registration_id registration_state registration_kind original_exam_session_id author_type created_at]}]
  (let [sql-format           #(cond
                                (string? %)
                                (str "'" % "'")
                                (inst? %)
                                (str "'" % "'")
                                (nil? %)
                                "NULL"
                                :else
                                %)
        sql-formatted-values (str "("
                                  (->> [event exam_session_id registration_id registration_state registration_kind original_exam_session_id author_type created_at]
                                       (map sql-format)
                                       (str/join ","))
                                  ")")]
    (base/execute! (str "INSERT INTO registration_change_event (event, exam_session_id, registration_id, registration_state, registration_kind, original_exam_session_id, author_type, created_at) VALUES " sql-formatted-values))))

(deftest exam-session-statistics-handler-test
  (base/insert-base-data)
  (let [handler                               (ig/init-key :yki.job.scheduled-tasks/exam-session-statistics-handler {:db (base/db)})
        clear-task-lock!                      #(base/execute! "UPDATE task_lock SET last_executed='-infinity' WHERE task='EXAM_SESSION_STATISTICS_HANDLER'")
        exam-session-id                       (:id (base/select-one "SELECT id FROM exam_session"))
        select-latest-exam-session-statistics #(base/select-one (str "SELECT * FROM exam_session_statistics WHERE exam_session_id=" exam-session-id " ORDER BY id DESC LIMIT 1"))
        participant-1                         (:id (base/select-one base/select-participant))
        participant-2                         (inc participant-1)]
    (testing "initial state"
      (is (= nil (select-latest-exam-session-statistics))))
    (testing "no exam session statistics are generated if time of execution is not roughly between registration start date and exam date"
      (clear-task-lock!)
      (handler)
      (is (= nil (select-latest-exam-session-statistics))))
    (testing "first run of handler yields initial statistics for exam session based on actual registration counts"
      (base/execute! "UPDATE exam_date SET registration_start_date=(current_date - interval '1 week'), exam_date=(current_date + interval '2 weeks')")
      (base/execute! (str "UPDATE exam_session SET max_participants=1 WHERE id=" exam-session-id))
      (base/execute! (str "INSERT INTO registration (participant_id, exam_session_id, state, kind) VALUES ("
                          (str/join "," [participant-1, exam-session-id, "'STARTED'", "'ADMISSION'"])
                          "), ("
                          (str/join "," [participant-2, exam-session-id, "'STARTED'", "'QUEUE'"])
                          ")"))
      (clear-task-lock!)
      (handler)
      (let [{:keys [participants max_participant_count queue max_queue_count]} (select-latest-exam-session-statistics)]
        (is (= 1 participants max_participant_count queue max_queue_count))))
    (testing "later runs of handler aggregate change events on top of initial statistics entry"
      (let [now (t/now)]
        ; +1 to queue, +1 to max_queue
        (insert-change-event! {:event              "CREATE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "STARTED"
                               :registration_kind  "QUEUE"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 1))})
        ; +1 to queue, +1 to max_queue
        (insert-change-event! {:event              "CREATE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "STARTED"
                               :registration_kind  "QUEUE"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 2))})
        ; +1 to participants, +1 to max_participants, -1 to queue
        (insert-change-event! {:event              "LIFT_FROM_QUEUE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "SUBMITTED"
                               :registration_kind  "ADMISSION"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 3))})
        ; no change to counts
        (insert-change-event! {:event              "COMPLETE_PAYMENT"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "COMPLETED"
                               :registration_kind  "ADMISSION"
                               :author_type        "INTEGRATION"
                               :created_at         (t/plus now (t/seconds 4))})
        (clear-task-lock!)
        (handler)
        (is (= {:participants          2
                :queue                 2
                :max_participant_count 2
                :max_queue_count       3}
               (-> (select-latest-exam-session-statistics)
                   (select-keys [:participants :queue :max_participant_count :max_queue_count])))))
      (let [now (t/now)]
        ; +1 to max_participants, +1 to participants
        (insert-change-event! {:event              "CREATE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "STARTED"
                               :registration_kind  "ADMISSION"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 1))})
        ; no change to counts
        (insert-change-event! {:event              "SUBMIT"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "SUBMITTED"
                               :registration_kind  "ADMISSION"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 2))})
        ; -1 to participants
        (insert-change-event! {:event              "EXPIRE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "PAID_AND_CANCELLED"
                               :registration_kind  "ADMISSION"
                               :author_type        "AUTOMATION"
                               :created_at         (t/plus now (t/seconds 3))})
        ; -1 to queue
        (insert-change-event! {:event              "CANCEL"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "CANCELLED"
                               :registration_kind  "QUEUE"
                               :author_type        "CLERK"
                               :created_at         (t/plus now (t/seconds 4))})
        (clear-task-lock!)
        (handler)
        (is (= {:participants          2
                :queue                 1
                :max_participant_count 3
                :max_queue_count       3}
               (-> (select-latest-exam-session-statistics)
                   (select-keys [:participants :queue :max_participant_count :max_queue_count])))))
      (let [now (t/now)]
        (base/insert-exam-session 2 (:oid base/organizer) 1)
        ; +1 to participants
        (insert-change-event! {:event              "CREATE"
                               :exam_session_id    exam-session-id
                               :registration_id    1
                               :registration_state "STARTED"
                               :registration_kind  "ADMISSION"
                               :author_type        "USER"
                               :created_at         (t/plus now (t/seconds 1))})
        ; relocate TO exam session of interest
        ; =>
        ; +1 to participants, +1 to max_participants
        (insert-change-event! {:event                    "RELOCATE"
                               :exam_session_id          exam-session-id
                               :registration_id          1
                               :registration_state       "COMPLETED"
                               :registration_kind        "ADMISSION"
                               :author_type              "USER"
                               :original_exam_session_id (inc exam-session-id)
                               :created_at               (t/plus now (t/seconds 2))})
        ; relocate FROM exam session of interest
        ; =>
        ; -1 to participants
        (insert-change-event! {:event                    "RELOCATE"
                               :exam_session_id          (inc exam-session-id)
                               :registration_id          1
                               :registration_state       "COMPLETED"
                               :registration_kind        "ADMISSION"
                               :author_type              "USER"
                               :original_exam_session_id exam-session-id
                               :created_at               (t/plus now (t/seconds 3))})
        (clear-task-lock!)
        (handler)
        (is (= {:participants          3
                :queue                 1
                :max_participant_count 4
                :max_queue_count       3}
               (-> (select-latest-exam-session-statistics)
                   (select-keys [:participants :queue :max_participant_count :max_queue_count]))))))))
