(ns yki.util.payment-util-test
  (:require [clojure.test :refer :all]
            [yki.spec :as ys]
            [jsonista.core :as j]
            [yki.util.payment-util :as payment]))

(def payment-form-data {:uri "https://paytrail.com",
                        :pt-payment-form-params {:REFERENCE_NUMBER 12344
                                                 :MERCHANT_ID 12345
                                                 :URL_SUCCESS "https://paytrail.com/payment/success"
                                                 :AMOUNT "100.00"
                                                 :PARAMS_OUT "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS"
                                                 :URL_CANCEL "https://paytrail.com/payment/cancel"
                                                 :LOCALE "fi_FI"
                                                 :PARAMS_IN "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,AMOUNT,ORDER_NUMBER,REFERENCE_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL,PARAMS_IN,PARAMS_OUT"
                                                 :authcode "73EF2A4EDD7A7FBF07FD5F6FAF99674DC0C25A025FD74C221F4C35849E5C0FB3"
                                                 :MSG_SETTLEMENT_PAYER "msg"
                                                 :MSG_UI_MERCHANT_PANEL "msg"
                                                 :ORDER_NUMBER "1234"}})

(deftest generate-payment-form-data
  (let [payment-config {:paytrail-host "https://paytrail.com"
                        :yki-paytrail-uri "https://paytrail.com/payment"
                        :merchant-id 12345
                        :merchant-secret "SECRET_KEY"}
        payment #::ys{:language-code "fi"
                      :amount 100.00
                      :order-number "1234"
                      :reference-number 12344
                      :msg "msg"}
        form-data (payment/generate-form-data payment-config payment)]
    (is (= payment-form-data form-data))))

