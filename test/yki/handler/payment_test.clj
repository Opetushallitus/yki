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
        payment-config {:paytrail-host "https://payment.paytrail.com/e2"
                        :yki-payment-uri "http://localhost:8080/yki/payment"
                        :merchant-id 12345
                        :amount "100.00"
                        :merchant-secret "SECRET_KEY"
                        :msg {:fi "msg_fi"
                              :sv "msg_sv"}}
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :domain "localhost"
                                                           :path "/yki"}}})
        url-helper (base/create-url-helper "localhost:8080")
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))
        payment-handler (middleware/wrap-format (ig/init-key :yki.handler/payment {:db db :payment-config payment-config :url-helper url-helper}))]
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
  "?PAYMENT_METHOD=1&AMOUNT=100.00&ORDER_NUMBER=order1234&PAYMENT_ID=101047298871&TIMESTAMP=1541146922&STATUS=PAID&RETURN_AUTHCODE=521A90258D9738692115A4B4F5F41256DFAAAF4249C6F81D0D9D4A9292835F08")

(def cancel-params
  "?PAYMENT_METHOD=1&AMOUNT=100.00&ORDER_NUMBER=order1234&PAYMENT_ID=101047298871&TIMESTAMP=1541146922&STATUS=PAID&RETURN_AUTHCODE=521A90258D9738692115A4B4F5F41256DFAAAF4249C6F81D0D9D4A9292835F08")

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
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM registration")) {:state "INCOMPLETE"}))
      (is (= (first (jdbc/query @embedded-db/conn "SELECT state FROM payment")) {:state "UNPAID"}))
      (is (s/includes? location "state=error")))))

(deftest handle-payment-cancel
  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/cancel" success-params)))
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

