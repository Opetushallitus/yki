(ns yki.handler.exam-session-public-test
  (:require [clojure.test :refer :all]
            [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.handler.base-test :as base]
            [muuntaja.middleware :as middleware]
            [yki.handler.base-test :as base]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.boundary.exam-session-db]
            [yki.handler.exam-session-public]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/exam-session-public {:db (base/db)}))]
    (handler request)))

(deftest get-exam-sessions-test
  (base/insert-base-data)
  (let [request (mock/request :get routing/exam-session-public-api-root)
        response (send-request request)
        response-body (base/body-as-json response)]
    (testing "get exam sessions endpoint should return 200"
      (is (= (:status response) 200))
      (is (= (count (response-body "exam_sessions")) 1))))

  (let [request (mock/request :get (str routing/exam-session-public-api-root "/1"))
        response (send-request request)
        response-body (base/body-as-json response)]
    (testing "get status endpoint should return 200"
      (is (= (:status response) 200)))))


