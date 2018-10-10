(ns yki.handler.exam-session-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.handler.file]
            [yki.handler.organizer]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest exam-session-validation-test
  (let [invalid-exam-session (base/change-entry base/exam-session "registration_start_time" "2019-03-01")
        request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") invalid-exam-session)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)
        response-body (base/body-as-json response)]
    (testing "post exam session endpoint should return 400 status code for validation errors"
      (is (= '({:count 0})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM exam_session")))
      (is (= (:status response) 400)))))

(deftest exam-session-crud-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-languages "'1.2.3.4'")

  (let [request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") base/exam-session)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)
        response-body (base/body-as-json response)]
    (testing "post exam session endpoint should return add valid exam session to database"
      (is (= '({:count 1})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM exam_session")))
      (is (= '({:count 3})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM exam_session_location")))
      (is (= (:status response) 200))))

  (let [request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-session"))
        response (base/send-request-with-tx request)
        response-body (base/body-as-json response)]
    (testing "get exam session endpoint should return exam session with location"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8")))
    (is (= (:status response) 200))
    (is (= response-body base/exam-sessions-json)))

  (let [updated-exam-session (base/change-entry base/exam-session "registration_start_time" "10:00")
        request (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.4/exam-session/1") updated-exam-session)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)]
    (testing "put exam session endpoint should update exam session based on id query parameter"
      (is (= '({:registration_start_time "10:00"})
             (jdbc/query @embedded-db/conn "SELECT registration_start_time FROM exam_session where id = 1")))))

  (let [request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4/exam-session/1"))
        response (base/send-request-with-tx request)]
    (testing "delete exam session endpoint should remove exam session and it's location"
      (is (= (:status response) 200))
      (is (= '({:count 0})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM exam_session")))
      (is (= '({:count 0})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM exam_session_location"))))))

