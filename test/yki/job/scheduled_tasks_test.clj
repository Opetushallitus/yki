(ns yki.job.scheduled-tasks-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.java.jdbc :as jdbc]
    [clj-time.core :as t]
    [integrant.core :as ig]
    [jsonista.core :as j]
    [pgqueue.core :as pgq]
    [stub-http.core :refer [with-routes!]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.onr :as onr]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.job.scheduled-tasks]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def participant-onr-map {"5.4.3.2.2" "301079-900U" "5.4.3.2.1" "010199-9012" "5.4.3.2.4" "301079-083N" })

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
    {"/organisaatio-service/rest/organisaatio/v4/1.2.3.4"   {:status       200
                                                             :content-type "application/json"
                                                             :body         (slurp "test/resources/organization.json")}
     "/organisaatio-service/rest/organisaatio/v4/1.2.3.4.5" {:status       200
                                                             :content-type "application/json"
                                                             :body         (slurp "test/resources/organization.json")}
     "/yki-sp/oph/tutkinto"                                 {:status       201
                                                             :content-type "application/json"
                                                             :body         "{}"}
     "/yki-sp/oph/tutkintotilaisuus"                        {:status       201
                                                             :content-type "application/json"
                                                             :body         "{}"}
     "/yki-sp/oph/jarjestaja"                               {:status       201
                                                             :content-type "application/json"
                                                             :body         "{}"}}
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
  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set exam_date = '" (base/two-weeks-from-now) "'"))
  (with-routes!
    {"/yki-sp/oph/osallistujat"                                             {:status 200
                                                                             :body   "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
      (with-redefs [onr/list-ssn-by-oids (constantly participant-onr-map )]
        (let [handler     (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db                     (base/db)
                                                                                           :disabled               false
                                                                                           :basic-auth             {:user "user" :password "pass"}
                                                                                           :retry-duration-in-days 14
                                                                                           :url-helper             (base/create-url-helper (str "localhost:" port))})
              _           (handler)
              sync_status (base/select-one "SELECT * FROM participant_sync_status")]
          (testing "should send participants to yki register and set sync status to success"
            (is (= (count (:recordings (first @(:routes server)))) 1))
            (is (some? (:success_at sync_status))))
          (testing "should send participants only once"
            (handler)
            (is (= (count (:recordings (first @(:routes server)))) 1)))))))

(deftest handle-exam-session-participants-failure-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")

  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set registration_end_date = '" (base/two-weeks-ago) "'"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant_sync_status (exam_session_id, failed_at) VALUES (1, '" (base/yesterday) "')"))

  (with-routes!
    {"/yki-sp/oph/osallistujat"                                             {:status 500
                                                                             :body   "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
      (with-redefs [onr/list-ssn-by-oids (constantly participant-onr-map)]
        (let [handler          (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db                     (base/db)
                                                                                                :disabled               false
                                                                                                :basic-auth             {:user "user" :password "pass"}
                                                                                                :retry-duration-in-days 14
                                                                                                :url-helper             (base/create-url-helper (str "localhost:" port))})
              failed_at_before (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))
              _                (handler)
              failed_at_after  (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))]
          (testing "should update failed at timestamp"
            (is (t/after? failed_at_after failed_at_before)))))))
