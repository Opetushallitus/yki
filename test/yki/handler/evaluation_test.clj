(ns yki.handler.evaluation-test
  (:require [clojure.test :refer :all]
            [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [peridot.core :as peridot]
            [duct.database.sql]
            [stub-http.core :refer :all]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [muuntaja.middleware :as middleware]
            [compojure.core :as core]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]
            [yki.handler.evaluation]))

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (f))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)
(def payment-config
  {:paytrail-host  "https://payment.paytrail.com/e2"
   :yki-payment-uri "http://localhost:3000/yki/evaluation-payment"
   :merchant_id 13466
   :merchant_secret "6pKF4jkv97zmqBJ3ZL8gUw5DfT2NMQ"
   :amount {:READING "50.00"
            :LISTENING "50.00"
            :WRITING "50.00"
            :SPEAKING "50.00"}
   :test_mode true})

(def mock-evaluation-order
  {:first_names "Mary Änne"
   :last_name "Smith"
   :email "mary.anne@testing.com"
   :phone_number "04012312132"
   :subtests ["WRITING"
              "READING"]})

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/evaluation {:db (base/db)
                                                           :payment-config {:amount {:READING "50.00"
                                                                                     :LISTENING "50.00"
                                                                                     :WRITING "50.00"
                                                                                     :SPEAKING "50.00"}}}))]
    (handler request)))

(deftest handle-evaluation-periods
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status       200
                                               :content-type "application/json"
                                               :body         (slurp "test/resources/localisation.json")}}
    (let [request            (mock/request :get (str routing/evaluation-root))
          response           (send-request request)
          response-body      (base/body-as-json response)
          evaluation-periods (response-body "evaluation_periods")
          open-evaluations   (filter (fn [x] (= (x "open") true)) evaluation-periods)]
      (testing "evaluation GET should return 200"
        (is (= (:status response) 200)))

      (testing "evaluation GET should only return ongoing or future evaluation periods"
        (is (= (count evaluation-periods) 3)))

      (testing "ongoing evaluation period should have open status true"
        (is (= (count open-evaluations) 1))
        (is (= ((first open-evaluations) "exam_date") (base/two-weeks-ago)))))

    (let [evaluation-id (:id (base/select-one (base/select-evaluation-by-date (base/two-weeks-ago))))
          request       (mock/request :get (str routing/evaluation-root "/" evaluation-id))
          response      (send-request request)
          response-body (base/body-as-json response)]
      (testing "evaluation/:id GET should return 200"
        (is (= (:status response) 200))
        (is (= (response-body "id") evaluation-id))))))

(deftest handle-evaluation-order
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status       200
                                               :content-type "application/json"
                                               :body         (slurp "test/resources/localisation.json")}}
    (let [evaluation       (base/select-one (base/select-evaluation-by-date (base/two-weeks-ago)))
          evaluation-id    (:id evaluation)
          request          (-> (mock/request :post (str routing/evaluation-root "/" evaluation-id "/order")
                                             (j/write-value-as-string   mock-evaluation-order))
                               (mock/content-type "application/json; charset=UTF-8"))
          response         (send-request request)
          response-body    (base/body-as-json response)
          order-id         (response-body "evaluation_order_id")
          order            (base/select-one (str "SELECT * FROM evaluation_order WHERE id=" order-id))
          order-subtests   (base/select (str "SELECT subtest FROM evaluation_order_subtest WHERE evaluation_order_id=" order-id))
          payment-id       (base/select-one (str "SELECT id FROM evaluation_payment WHERE evaluation_order_id=" order-id))
          order-comparison (map (fn [x] (= (order  x) (mock-evaluation-order  x)))  [:first_names :last_name :email :phone_number])]
      (testing "can post a valid evaluation order"
        (is (= (:status response) 200))
        (is (every? true? order-comparison))
        (is (= (count (:subtests mock-evaluation-order)) (count order-subtests)))
        (is (some? payment-id))))))



