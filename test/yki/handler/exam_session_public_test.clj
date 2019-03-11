(ns yki.handler.exam-session-public-test
  (:require [clojure.test :refer :all]
            [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [yki.handler.base-test :as base]
            [muuntaja.middleware :as middleware]
            [yki.handler.base-test :as base]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.boundary.exam-session-db]
            [yki.handler.exam-session-public]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))
(use-fixtures :each embedded-db/with-transaction)

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/exam-session-public {:db (base/db)
                                                                    :payment-config {:amount {:PERUS "100.00"
                                                                                              :KESKI "123.00"
                                                                                              :YLIN "160.00"}}}))]
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
      (is (= (response-body "exam_fee") "100.00"))
      (is (= (:status response) 200))))

  (let [request (mock/request :get (str routing/exam-session-public-api-root "/123"))
        response (send-request request)]
    (testing "get status endpoint should return 404"
      (is (= (:status response) 404)))))

(deftest post-exam-session-queue-test
  (base/insert-base-data)
  (let [id (:id (base/select-one "SELECT id from exam_session"))
        request (-> (mock/request :post (str routing/exam-session-public-api-root "/" id "/queue?lang=sv")
                                  (j/write-value-as-string {:email "test@test.com"}))
                    (mock/content-type "application/json; charset=UTF-8"))
        response (send-request request)
        response-body (base/body-as-json response)
        exam-session-queue (base/select "SELECT * from exam_session_queue")]
    (testing "post exam session queue endpoint should add email to queue"
      (is (= (:status response) 200))
      (is (= (count exam-session-queue) 1)))))


