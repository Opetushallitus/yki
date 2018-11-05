(ns yki.payment.payment-util-test
  (:require [clojure.test :refer :all]
            [yki.spec :as ys]
            [jsonista.core :as j]
            [yki.payment.payment-util :as payment]))

(def payment-form-data {:uri "https://paytrail.com",
                        :pt-payment-form-params {:REFERENCE_NUMBER 12344
                                                 :MERCHANT_ID 12345
                                                 :URL_SUCCESS "http://localhost:8080/payment/success"
                                                 :AMOUNT "100.00"
                                                 :PARAMS_OUT "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS,PAYMENT_METHOD"
                                                 :URL_CANCEL "http://localhost:8080/payment/cancel"
                                                 :URL_NOTIFY "http://localhost:8080/payment/notify"
                                                 :LOCALE "fi_FI"
                                                 :PARAMS_IN "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,URL_NOTIFY,AMOUNT,ORDER_NUMBER,REFERENCE_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL,PARAMS_IN,PARAMS_OUT"
                                                 :AUTHCODE "73EF2A4EDD7A7FBF07FD5F6FAF99674DC0C25A025FD74C221F4C35849E5C0FB3"
                                                 :MSG_SETTLEMENT_PAYER "msg_fi"
                                                 :MSG_UI_MERCHANT_PANEL "msg_fi"
                                                 :ORDER_NUMBER "1234"}})
(def payment-config {:paytrail-host "https://paytrail.com"
                     :yki-payment-uri "http://localhost:8080/payment"
                     :merchant-id 12345
                     :amount "100.00"
                     :merchant-secret "SECRET_KEY"
                     :msg {:fi "msg_fi"
                           :sv "msg_sv"}})

(deftest generate-payment-form-data
  (let [payment {:language-code "fi"
                 :order-number "1234"
                 :reference-number 12344}
        form-data (payment/generate-form-data payment-config payment)]
    (testing "should create payment form data"
      (is (= payment-form-data form-data)))))

(deftest validate-return-params
  (let [query-params {:PAYMENT_ID "101047298871"
                      :TIMESTAMP "1541146922"
                      :ORDER_NUMBER "1234"
                      :AMOUNT "100.00"
                      :STATUS "PAID"
                      :PAYMENT_METHOD "1"
                      :RETURN_AUTHCODE "B4479EF810E59170D46B11A512C726650B57E08E1BD1EC13018C45BCB2A14D99"}
        result (payment/valid-return-params? payment-config query-params)]
    (testing "should validate form params"
      (is (= result true)))))

