(ns yki.handler.organizer-test
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

(deftest organizer-validation-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (let [json-body (j/write-value-as-string (assoc-in base/organization [:agreement_start_date] "NOT_A_VALID_DATE"))
          request (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)]
      (testing "post organization endpoint should return 400 status code for validation errors"
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
        (is (= (:status response) 400))))))

(deftest update-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (base/insert-organization tx "'1.2.3.5'")
    (let [json-body (j/write-value-as-string base/organization)
          request (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.5") json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)]
      (testing "put organization endpoint should update organization based on oid in url params"
        (is (= '({:count 2})
               (jdbc/query tx "SELECT COUNT(1) FROM exam_language where organizer_id = (SELECT id FROM organizer WHERE oid = '1.2.3.5' AND deleted_at IS NULL)")))
        (is (= '({:contact_name "fuu"})
               (jdbc/query tx "SELECT contact_name FROM organizer where oid = '1.2.3.5'")))
        (is (= (:status response) 200))))))

(deftest add-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (let [json-body (j/write-value-as-string base/organization)
          request (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request tx request)]
      (testing "post organization endpoint should add organization"
        (is (= '({:count 1})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
        (is (= (:status response) 200))))))

(deftest get-organizations-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (base/insert-organization tx "'1.2.3.4'")
    (base/insert-organization tx "'1.2.3.5'")
    (base/insert-languages tx "'1.2.3.4'")
    (let [request (mock/request :get routing/organizer-api-root)
          response (base/send-request tx request)
          response-body (base/body-as-json response)]
      (testing "get organizations endpoint should return 2 organizations with exam levels"
        (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8")))
      (is (= (:status response) 200))
      (is (= response-body base/organizations-json)))))

(deftest delete-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (base/insert-organization tx "'1.2.3.4'")
    (let [request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4"))
          response (base/send-request tx request)]
      (testing "delete organization endpoint should remove organization"
        (is (= (:status response) 200))
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer where deleted_at IS NULL")))))))
