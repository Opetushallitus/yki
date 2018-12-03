(ns yki.job.scheduled-tasks-test
  (:require [clojure.test :refer :all]
            [stub-http.core :refer :all]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [clojure.java.jdbc :as jdbc]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.job.scheduled-tasks :as st]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def email-req
  {:recipients ["test@test.com"]
   :created (System/currentTimeMillis)
   :subject "subject"
   :body "body"})

(defn create-email-q-reader
  [port retry-duration-in-days]
  (ig/init-key :yki.job.scheduled-tasks/email-queue-reader {:url-helper (base/create-url-helper (str "localhost:" port))
                                                            :retry-duration-in-days retry-duration-in-days
                                                            :email-q (base/email-q)}))
(deftest handle-email-request-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 200 :content-type "application/json"
                                                :body   (j/write-value-as-string {:id 1})}}
    (let [email-q (base/email-q)
          _ (pgq/put email-q email-req)
          reader (create-email-q-reader port 1)]
      (testing "should read email request from queue and send email"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (count (:recordings (first @(:routes server)))) 1))
        (is (= (pgq/count email-q) 0))))))

(deftest handle-started-registration-expired-test
  (base/insert-login-link-prereqs)
  (jdbc/execute! @embedded-db/conn (str
                                    "INSERT INTO registration(state, exam_session_id, participant_id, started_at) values ('STARTED'," base/select-exam-session "," base/select-participant ", (current_timestamp - interval '61 minutes'))"))

  (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-state-handler {:db (duct.database.sql/->Boundary @embedded-db/conn)})
        _ (registration-state-handler)
        registration (base/select-one "SELECT * FROM registration")]
    (testing "should set state of registration started over 1 hour ago to expired"
      (is (= (:state registration) "EXPIRED")))))

(deftest handle-submitted-registration-expired-test
  (base/insert-login-link-prereqs)
  (base/insert-payment)
  (jdbc/execute! @embedded-db/conn
                 "UPDATE payment SET created = (current_timestamp - interval '193 hours')")
  (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-state-handler {:db (duct.database.sql/->Boundary @embedded-db/conn)})
        _ (registration-state-handler)
        registration (base/select-one "SELECT * FROM registration")]
    (testing "when submitted registration has not been payed in 8 days then state is set to expired"
      (is (= (:state registration) "EXPIRED")))))

(deftest handle-exam-session-request-test
  (base/insert-login-link-prereqs)
  (with-routes!
    {"/organisaatio-service/rest/organisaatio/v4/1.2.3.4" {:status 200
                                                           :content-type "application/json"
                                                           :body   (slurp "test/resources/organization.json")}
     "/tutkintotilaisuus" {:status 200
                           :content-type "application/json"
                           :body   "{}"}
     "/jarjestaja" {:status 200
                    :content-type "application/json"
                    :body   "{}"}}
    (let [data-sync-q  (base/data-sync-q)
          exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
          _ (pgq/put data-sync-q  {:exam-session-id exam-session-id
                                   :created (System/currentTimeMillis)})
          reader (ig/init-key :yki.job.scheduled-tasks/data-sync-queue-reader {:url-helper (base/create-url-helper (str "localhost:" port))
                                                                               :db (base/db)
                                                                               :disabled false
                                                                               :retry-duration-in-days 1
                                                                               :data-sync-q  data-sync-q})]
      (testing "should read email request from queue and send email"
        (is (= (pgq/count data-sync-q) 1))
        (reader)
        (is (= (pgq/count data-sync-q) 0))))))

(deftest queue-reader-retry-if-execution-fails-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 500 :content-type "application/json"
                                                :body   (j/write-value-as-string {:error "fail"})}}
    (let [email-q (base/email-q)
          _ (pgq/put email-q email-req)
          reader (create-email-q-reader port 1)]
      (testing "should return request to queue if execution fails"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (pgq/count email-q) 1))))))

(deftest queue-reader-no-retry-when-retry-duration-reached-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 500 :content-type "application/json"
                                                :body   (j/write-value-as-string {:error "fail"})}}
    (let [email-q (base/email-q)
          _ (pgq/put email-q email-req)
          reader (create-email-q-reader port 0)]
      (testing "should remove message from queue"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (pgq/count email-q) 0))))))

