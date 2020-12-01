(ns yki.handler.exam-date-public-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [yki.handler.base-test :as base]
            [compojure.api.sweet :refer :all]
            [jsonista.core :as j]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.exam-date-public]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/exam-date-public {:db (base/db)}))]
    (handler request)))

(deftest get-exam-dates-test
  (let [request (mock/request :get routing/exam-date-api-root)
        response (send-request request)]
    (testing "should return 200"
      (is (= (:status response) 200)))))

