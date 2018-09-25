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
    (let [json-body (j/write-value-as-string (assoc-in base/organization [:agreement_start_date] "NOT_A_VALID_DATE"))
          request (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)]
      (testing "post organization endpoint should return 400 status code for validation errors"
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
        (is (= (:status response) 400))))))

