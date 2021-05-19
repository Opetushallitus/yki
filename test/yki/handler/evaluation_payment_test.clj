(ns yki.handler.evaluation-payment-test
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
            [jsonista.core :as j]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]
            [yki.handler.evaluation-payment]))

(def test-order {:first_names "Anne Marie"
                 :last_name "Jones"
                 :email "anne-marie.jones@testi.fi"
                 :birthdate "2000-02-14"})

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (base/insert-evaluation-payment-data test-order)
  (f))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction insert-prereq-data)

(defn- send-request [request port]
  (let [handler (api (ig/init-key :yki.handler/evaluation-payment {:db (base/db)
                                                                   :payment-config   {:paytrail-host  "https://payment.paytrail.com/e2"
                                                                                      :yki-payment-uri "http://localhost:8080/yki/api/evaluation-payment"
                                                                                      :merchant_id "12345"
                                                                                      :merchant_secret "6pKF4jkv97zmqBJ3ZL8gUw5DfT2NMQ"
                                                                                      :kirjaamo-email "kirjaamo@testi.fi"
                                                                                      :amount {:READING "50.00"
                                                                                               :LISTENING "50.00"
                                                                                               :WRITING "50.00"
                                                                                               :SPEAKING "50.00"}}
                                                                   :url-helper (base/create-url-helper (str "localhost:" port))
                                                                   :email-q (base/email-q)}))]
    (handler request)))

(deftest handle-evaluation-payment-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [evaluation-order    (base/get-evaluation-order-and-status test-order)
          evaluation-order-id (:id evaluation-order)
          request             (mock/request :get (str routing/evaluation-payment-root "/formdata?evaluation-order-id=" evaluation-order-id))
          response            (send-request request port)
          response-body       (base/body-as-json response)]
      (testing "evaluation payment form data endpoint should return payment url and formdata "
        (is (= (get-in response [:status]) 200))
        (is (= base/evaluation-payment-formdata-json response-body))))))

(def success-params
  "?ORDER_NUMBER=YKI-EVA-TEST&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=PAID&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=B091DCB9EA6C08900D944CA0924531C21B94406FA40345681B97CA835F4666DB")

(def cancel-params
  "?ORDER_NUMBER=order1234&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=CANCELLED&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=7874413040C8F6BF5D005B6BD3F22C14AB1C99B841C3FE7733E9D12D5D7F4175")

(deftest handle-evaluation-payment-success-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [evaluation-order (base/get-evaluation-order-and-status test-order)
          request          (mock/request :get (str routing/evaluation-payment-root "/success" success-params))
          response         (send-request request port)
          location         (get-in response [:headers "Location"])
          customer-email   (pgq/take (base/email-q))
          kirjaamo-email   (pgq/take (base/email-q))
          includes-all     (fn [email-body subStrings] (every? true? (map (fn [subStr] (s/includes? email-body subStr)) subStrings)))]

      (testing "evaluation order payment state should be UNPAID before paytrail callback"
        (is (= "UNPAID" (:state evaluation-order))))

      (testing "when payment is successful, evaluation payment status should be PAID"
        (is (= "PAID" (:state (base/get-evaluation-payment-status-by-orde-id (:id evaluation-order))))))

      (testing "when payment is successful, user should be redirected to correct success url"
        (is (s/includes? location (str "tila?status=payment-success&lang=fi&id=" (:id evaluation-order)))))

      (testing "should send an email to kirjaamo"
        (is (= "kirjaamo@testi.fi" (first (:recipients kirjaamo-email))))
        (is (includes-all (:body kirjaamo-email) ["YKI-EVA-TEST" "suomi perustaso" "Luetun ymmärtäminen" "Puheen ymmärtäminen"])))

      (testing "should send an email to customer"
        (is (= (:email test-order) (first (:recipients customer-email))))
        (is (includes-all (:body customer-email) ["suomi perustaso" "Luetun ymmärtäminen" "Puheen ymmärtäminen"]))))))
