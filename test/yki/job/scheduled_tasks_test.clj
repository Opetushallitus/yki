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

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(def email-req
  {:recipients ["test@test.com"]
   :subject "subject"
   :body "body"})

(deftest handle-email-request-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 200 :content-type "application/json"
                                                :body   (j/write-value-as-string {:id 1})}}
    (let [email-q (ig/init-key :yki.job.job-queue/email-q {:db-config {:db embedded-db/db-spec}})
          _ (pgq/put email-q email-req)
          reader (ig/init-key :yki.job.scheduled-tasks/email-queue-reader {:url-helper (base/create-url-helper (str "localhost:" port)) :email-q email-q})]
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
