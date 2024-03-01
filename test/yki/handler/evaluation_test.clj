(ns yki.handler.evaluation-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [compojure.api.sweet :refer [api]]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.evaluation]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.util.paytrail-payments :refer [sign-string]]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(def mock-evaluation-order
  {:first_names "Mary Änne"
   :last_name   "Smith"
   :email       "mary.anne@testing.com"
   :birthdate   "2001-01-18"
   :subtests    ["WRITING"
                 "READING"]})

(defn- create-handler [port]
  (let [db             (base/db)
        url-helper     (base/create-url-helper (str "localhost:" port))
        payment-helper (base/create-evaluation-payment-helper db url-helper)]
    (api (ig/init-key :yki.handler/evaluation {:db             db
                                               :error-boundary (base/error-boundary)
                                               :payment-helper payment-helper}))))

(defn- evaluation-order-post [handler evaluation-id order]
  (let [request (-> (mock/request :post (str routing/evaluation-root "/" evaluation-id "/order?lang=fi")
                                  (j/write-value-as-string order))
                    (mock/content-type "application/json; charset=UTF-8"))]

    (handler request)))

(deftest handle-evaluation-periods
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (with-routes!
    {}
    (let [handler (create-handler port)]
      (let [request            (mock/request :get (str routing/evaluation-root))
            response           (handler request)
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
            response      (handler request)
            response-body (base/body-as-json response)]

        (testing "evaluation/:id GET should return 200"
          (is (= (:status response) 200))
          (is (= (response-body "id") evaluation-id)))))))

(deftest handle-evaluation-order-with-new-payments
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (with-routes!
    {}
    (let [open-evaluation-id   (:id (base/select-evaluation-by-date (base/two-weeks-ago)))
          closed-evaluation-id (:id (base/select-evaluation-by-date "2019-05-02"))
          handler              (create-handler port)]
      (let [response         (evaluation-order-post handler open-evaluation-id mock-evaluation-order)
            response-body    (base/body-as-json response)
            order-id         (response-body "evaluation_order_id")
            order            (base/select-one (str "SELECT * FROM evaluation_order WHERE id=" order-id))
            order-subtests   (base/select (str "SELECT subtest FROM evaluation_order_subtest WHERE evaluation_order_id=" order-id))
            {:keys [id href]} (base/select-one (str "SELECT id, href FROM evaluation_payment_new WHERE evaluation_order_id=" order-id))
            order-comparison (map (fn [x] (= (order x) (mock-evaluation-order x))) [:first_names :last_name :email :birthdate])]

        (testing "can post a valid evaluation order"
          (is (= (:status response) 200))
          (is (= response-body
                 {"evaluation_order_id" order-id
                  "signature"           (sign-string base/new-evaluation-payment-config (str order-id))
                  "redirect"            href}))
          (is (every? true? order-comparison))
          (is (= (count (:subtests mock-evaluation-order)) (count order-subtests)))
          (is (some? id))))

      (let [response      (evaluation-order-post handler closed-evaluation-id mock-evaluation-order)
            response-body (base/body-as-json response)]

        (testing "cannot post evaluation order when evaluation period has closed"
          (is (= (:status response) 409))
          (is (= (response-body "success") false))))

      (let [response      (evaluation-order-post handler open-evaluation-id (assoc mock-evaluation-order :subtests []))
            response-body (base/body-as-json response)]

        (testing "cannot post evaluation order with no subtests"
          (is (= (:status response) 422))
          (is (= (response-body "success") false))))

      (let [response      (evaluation-order-post handler open-evaluation-id (assoc mock-evaluation-order :subtests ["READING" "LISTENING" "LISTENING"]))
            response-body (base/body-as-json response)]

        (testing "cannot post evaluation order with duplicate subtests"
          (is (= (:status response) 422))
          (is (= (response-body "success") false)))))))
