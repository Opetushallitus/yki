(ns yki.handler.payment-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [peridot.core :as peridot]
            [duct.database.sql]
            [muuntaja.middleware :as middleware]
            [compojure.core :as core]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.payment.paytrail-payment]
            [yki.handler.payment]))

(defn insert-prereq-data [f]
  (base/insert-login-link-prereqs)
  (base/insert-login-link "4ce84260-3d04-445e-b914-38e93c1ef667" "2038-01-01")
  (f))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction insert-prereq-data)

(defn- create-handlers []
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :domain "localhost"
                                                           :path "/yki"}}})
        url-helper (base/create-url-helper "localhost:8080")
        email-q (ig/init-key :yki.job.job-queue/email-q {:db-config {:db embedded-db/db-spec}})
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))
        payment-handler (middleware/wrap-format (ig/init-key :yki.handler/payment {:db db
                                                                                   :payment-config base/payment-config
                                                                                   :url-helper url-helper
                                                                                   :email-q email-q}))]
    (core/routes auth-handler payment-handler)))

(deftest get-payment-formdata-test
  (let [registration-id (:registration_id (first (jdbc/query @embedded-db/conn "SELECT registration_id FROM payment")))
        handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/formdata?registration-id=" registration-id)))
        response-body (base/body-as-json (:response response))]
    (testing "payment form data endpoint should return payment url and formdata"
      (is (= (get-in response [:response :status]) 200))
      (is (= base/payment-formdata-json response-body)))))

(def success-params
  "?ORDER_NUMBER=order1234&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=PAID&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=312BF5EA52575FCEAECEE3A18153CB9C759E6CBFE2622670EC9902964C2C4EC5")

(def cancel-params
  "?ORDER_NUMBER=order1234&PAYMENT_ID=101687270712&AMOUNT=100.00&TIMESTAMP=1541585404&STATUS=CANCELLED&PAYMENT_METHOD=1&SETTLEMENT_REFERENCE_NUMBER=1232&RETURN_AUTHCODE=7874413040C8F6BF5D005B6BD3F22C14AB1C99B841C3FE7733E9D12D5D7F4175")

(deftest handle-payment-success-test
  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/success" success-params)))
        location (get-in response [:response :headers "Location"])]
    (testing "when payment is success should complete registration and redirect to success url"
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM registration")) {:state "COMPLETED"}))
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM payment")) {:state "PAID"}))
      (is (s/includes? location "state=success")))))

(deftest handle-payment-success-invalid-authcode-test
  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/success" success-params "INVALID")))
        location (get-in response [:response :headers "Location"])]
    (testing "when return authcode is invalid should redirect to error url"
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM registration")) {:state "SUBMITTED"}))
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM payment")) {:state "UNPAID"}))
      (is (s/includes? location "state=error")))))

(deftest handle-payment-cancel
  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/cancel" cancel-params)))
        location (get-in response [:response :headers "Location"])]
    (testing "when payment is cancelled should redirect to cancelled url"
      (is (s/includes? location "state=cancel")))))

(deftest handle-payment-notify-test
  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/notify" success-params)))
        status (get-in response [:response :status])]
    (testing "when payment success is notified should complete registration"
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM registration")) {:state "COMPLETED"}))
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM payment")) {:state "PAID"}))
      (is (= status 200)))))

