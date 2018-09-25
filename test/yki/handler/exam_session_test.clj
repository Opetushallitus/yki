(ns yki.handler.exam-session-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.handler.file]
            [yki.handler.organizer]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(deftest exam-session-validation-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (let [invalid-exam-session (base/change-entry base/exam-session "registration_start_time" "2019-03-01")
          request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") invalid-exam-session)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)
          response-body (base/body-as-json response)]
      (testing "post exam session endpoint should return 400 status code for validation errors"
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM exam_session")))
        (is (= (:status response) 400))))))

(deftest add-exam-session-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (base/insert-organization tx "'1.2.3.4'")
    (let [request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") base/exam-session)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)
          response-body (base/body-as-json response)]
      (testing "post exam session endpoint should return add valid exam session to database"
        (is (= '({:count 1})
               (jdbc/query tx "SELECT COUNT(1) FROM exam_session")))
        (is (= (:status response) 200))))))

