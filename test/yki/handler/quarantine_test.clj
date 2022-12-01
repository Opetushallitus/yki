(ns yki.handler.quarantine-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [ring.mock.request :as mock]
            [yki.embedded-db :as embedded-db]
            [yki.boundary.quarantine-db :as quarantine-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest get-quarantine-test
  (base/insert-quarantine)
  (let [request  (mock/request :get routing/quarantine-api-root)
        response (base/send-request-with-tx request)]
    (testing "get quarantine endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest get-quarantine-matches-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-quarantine)
  (let [request  (mock/request :get (str routing/quarantine-api-root "/matches"))
        response (base/send-request-with-tx request)]
    (testing "get quarantine endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest set-quarantine-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-quarantine)
  (let [request  (mock/request :get (str routing/quarantine-api-root "/set"))
        response (base/send-request-with-tx request)]
    (testing "get quarantine endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))
