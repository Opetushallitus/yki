(ns yki.handler.payment-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [peridot.core :as peridot]
            [duct.database.sql]
            [muuntaja.middleware :as middleware]
            [compojure.core :as core]
            [clojure.java.jdbc :as jdbc]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.payment.paytrail-payment]
            [yki.handler.payment]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

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
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))
        payment-handler (middleware/wrap-format (ig/init-key :yki.handler/payment {:db db :payment-config payment-config}))]
    (core/routes auth-handler payment-handler)))

(defn- insert-prereq-data []
  (base/insert-login-link-prereqs)
  (base/insert-login-link "4ce84260-3d04-445e-b914-38e93c1ef667" "2038-01-01"))

(deftest get-payment-formdata-test
  (insert-prereq-data)

  (let [handler (create-handlers)
        session (base/login-with-login-link (peridot/session handler))
        response (-> session
                     (peridot/request (str routing/payment-root "/formdata?registration-id=1")))
        response-body (base/body-as-json (:response response))]
    (testing "payment form data endpoint should return payment url and formdata"
      (is (= (get-in response [:response :status]) 200))
      (is (= base/payment-formdata-json response-body)))))

; (deftest handle-payment-success-test
;   (insert-prereq-data)

;   (let [handler (create-handlers)
;         session (base/login-with-login-link (peridot/session handler))
;         response (-> session
;                       (peridot/request (str routing/payment-root "/success")))
;         response-body (base/body-as-json (:response response))]
;     (testing "payment form data endpoint should return payment url and formdata"
;       (is (= (get-in response [:response :status]) 200))
;       (is (= base/payment-formdata-json response-body)))))

