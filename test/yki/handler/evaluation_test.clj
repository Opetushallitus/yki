(ns yki.handler.evaluation-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [compojure.api.sweet :refer [api]]
            [duct.database.sql]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.auth]
            [yki.handler.evaluation]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(def mock-evaluation-order
  {:first_names "Mary Änne"
   :last_name "Smith"
   :email "mary.anne@testing.com"
   :birthdate "2001-01-18"
   :subtests ["WRITING"
              "READING"]})

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/evaluation {:db (base/db)
                                                           :payment-config {:amount {:READING "50.00"
                                                                                     :LISTENING "50.00"
                                                                                     :WRITING "50.00"
                                                                                     :SPEAKING "50.00"}}}))]
    (handler request)))

(defn- evaluation-order-post [evaluation-id order]
  (let [request          (-> (mock/request :post (str routing/evaluation-root "/" evaluation-id "/order?lang=fi")
                                           (j/write-value-as-string  order))
                             (mock/content-type "application/json; charset=UTF-8"))]

    (send-request request)))

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

    (let [evaluation-id (:id (base/select-evaluation-by-date (base/two-weeks-ago)))
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

    (let [open-evaluation-id   (:id (base/select-evaluation-by-date (base/two-weeks-ago)))
          closed-evaluation-id (:id (base/select-evaluation-by-date "2019-05-02"))]

      (let [response         (evaluation-order-post open-evaluation-id mock-evaluation-order)
            response-body    (base/body-as-json response)
            order-id         (response-body "evaluation_order_id")
            order            (base/select-one (str "SELECT * FROM evaluation_order WHERE id=" order-id))
            order-subtests   (base/select (str "SELECT subtest FROM evaluation_order_subtest WHERE evaluation_order_id=" order-id))
            payment-id       (base/select-one (str "SELECT id FROM evaluation_payment WHERE evaluation_order_id=" order-id))
            order-comparison (map (fn [x] (= (order  x) (mock-evaluation-order  x)))  [:first_names :last_name :email :birthdate])]

        (testing "can post a valid evaluation order"
          (is (= (:status response) 200))
          (is (every? true? order-comparison))
          (is (= (count (:subtests mock-evaluation-order)) (count order-subtests)))
          (is (some? payment-id))))

      (let [response         (evaluation-order-post closed-evaluation-id mock-evaluation-order)
            response-body    (base/body-as-json response)]

        (testing "cannot post evaluation order when evaluation period has closed"
          (is (= (:status response) 409))
          (is (= (response-body "success") false))))

      (let [response         (evaluation-order-post open-evaluation-id (assoc mock-evaluation-order :subtests []))
            response-body    (base/body-as-json response)]

        (testing "cannot post evaluation order with no subtests"
          (is (= (:status response) 422))
          (is (= (response-body "success") false))))

      (let [response         (evaluation-order-post open-evaluation-id (assoc mock-evaluation-order :subtests ["READING" "LISTENING" "LISTENING"]))
            response-body    (base/body-as-json response)]

        (testing "cannot post evaluation order with duplicate subtests"
          (is (= (:status response) 422))
          (is (= (response-body "success") false)))))))



