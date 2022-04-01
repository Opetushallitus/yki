(ns yki.handler.payment-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [compojure.core :as core]
            [duct.database.sql]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [pgqueue.core :as pgq]
            [stub-http.core :refer [with-routes!]]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.registration.paytrail-payment]
            [yki.handler.payment]))

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-payment)
  (base/insert-login-link "4ce84260-3d04-445e-b914-38e93c1ef667" "2038-01-01")
  (f))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction insert-prereq-data)

(defn- create-handlers [port]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (base/create-url-helper (str "localhost:" port))
        auth (base/auth url-helper)
        access-log (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        auth-handler (base/auth-handler auth url-helper)
        payment-handler (middleware/wrap-format (ig/init-key :yki.handler/payment {:db db
                                                                                   :payment-config base/payment-config
                                                                                   :url-helper url-helper
                                                                                   :auth auth
                                                                                   :access-log access-log
                                                                                   :email-q (base/email-q)}))]
    (core/routes auth-handler payment-handler)))

(deftest get-payment-formdata-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [registration-id (:registration_id (first (jdbc/query @embedded-db/conn "SELECT registration_id FROM payment")))
          handler (create-handlers port)
          session (base/login-with-login-link (peridot/session handler))
          response (-> session
                       (peridot/request (str routing/payment-root "/formdata?registration-id=" registration-id)))
          response-body (base/body-as-json (:response response))]
      (testing "payment form data endpoint should return payment url and formdata"
        (is (= (get-in response [:response :status]) 200))
        (is (= base/payment-formdata-json response-body)))

      (let [_ (jdbc/execute! @embedded-db/conn "UPDATE payment_config SET test_mode = TRUE")
            test-mode-response (-> session
                                   (peridot/request (str routing/payment-root "/formdata?registration-id=" registration-id)))
            test-mode-response-body (base/body-as-json (:response test-mode-response))]
        (testing "should set payment to 1 euro when test mode is enabled"
          (is (= (get-in test-mode-response-body ["params" "AMOUNT"]) "1.00")))))))

(def success-params
  "?ORDER_NUMBER=order1234&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=PAID&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=312BF5EA52575FCEAECEE3A18153CB9C759E6CBFE2622670EC9902964C2C4EC5")

(def cancel-params
  "?ORDER_NUMBER=order1234&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=CANCELLED&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=7874413040C8F6BF5D005B6BD3F22C14AB1C99B841C3FE7733E9D12D5D7F4175")

(deftest handle-payment-success-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status       200
                                               :content-type "application/json"
                                               :body         (slurp "test/resources/localisation.json")}}
    (let [handler (create-handlers port)
          session (base/login-with-login-link (peridot/session handler))
          email-q (base/email-q)
          response (-> session
                       (peridot/request (str routing/payment-root "/success" success-params)))
          location (get-in response [:response :headers "Location"])
          email-req (pgq/take email-q)]
      (testing "when payment is success should complete registration and redirect to success url"
        (is (= (base/select-one "SELECT state FROM registration") {:state "COMPLETED"}))
        (is (= (base/select-one "SELECT state FROM payment") {:state "PAID"}))
        (is (s/includes? location "tila?status=payment-success&lang=fi&id=5")))

      (testing "confirmation email should be send"
        (is (some? email-req))
        (is (s/includes? (:body email-req) "Omenia, Upseerinkatu 11, 00240 Espoo"))))))

(deftest handle-payment-success-invalid-authcode-test
  (let [handler (create-handlers 8080)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/success" success-params "INVALID")))
        location (get-in response [:response :headers "Location"])]
    (testing "when return authcode is invalid should redirect to error url"
      (is (= (base/select-one "SELECT state FROM registration") {:state "SUBMITTED"}))
      (is (= (base/select-one "SELECT state FROM payment") {:state "UNPAID"}))
      (is (s/includes? location "maksu/tila?status=payment-error&lang=fi")))))

(deftest handle-payment-success-registration-not-found-test
  (jdbc/execute! @embedded-db/conn "DELETE FROM payment")
  (jdbc/execute! @embedded-db/conn "DELETE FROM registration")
  (let [handler (create-handlers 8080)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/success" success-params)))
        location (get-in response [:response :headers "Location"])]
    (testing "when return authcode is invalid should redirect to error url"
      (is (s/includes? location "tila?status=payment-error")))))

(deftest handle-payment-cancel
  (let [handler (create-handlers 8080)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/cancel" cancel-params)))
        location (get-in response [:response :headers "Location"])]
    (testing "when payment is cancelled should redirect to cancelled url"
      (is (s/includes? location "maksu/tila?status=payment-cancel&lang=fi")))))

(deftest handle-payment-notify-test
  (let [handler (create-handlers 8080)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/notify" success-params)))
        status (get-in response [:response :status])]
    (testing "when payment success is notified should complete registration"
      (is (= (base/select-one "SELECT state FROM registration") {:state "COMPLETED"}))
      (is (= (base/select-one "SELECT state FROM payment") {:state "PAID"}))
      (is (= status 200)))))

