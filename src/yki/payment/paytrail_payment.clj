(ns yki.payment.paytrail-payment
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [clojure.string :as str]
            [clj-time.coerce :as c]
            [yki.boundary.registration-db :as registration-db]
            [yki.payment.payment-util :as payment-util]
            [ring.util.http-response :refer [not-found]]
            [clojure.tools.logging :refer [info error]]
            [integrant.core :as ig]))

(defn- create-order-number [db external-user-id]
  (let [order-number-prefix (-> (str/split external-user-id #"\.") last)
        order-number-suffix (registration-db/get-next-order-number-suffix! db)]
    (str "YKI" order-number-prefix order-number-suffix)))

(defn create-payment-form-data
  [db payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (if-let [payment (registration-db/get-payment-by-registration-id db registration-id)]
      (let [payment-data {:language-code lang
                          :order-number (:order_number payment)}
            form-data (payment-util/generate-form-data payment-config payment-data)]
        form-data)
      (error "Payment not found for registration-id" registration-id))
    (error "Registration not found" registration-id)))

(defn valid-return-params? [payment-config params]
  (payment-util/valid-return-params? payment-config params))

(defn create-payment
  [db payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (let [order-number (create-order-number db external-user-id)
          payment-id (registration-db/create-payment! db {:registration_id registration-id
                                                          :amount (bigdec (payment-config :amount))
                                                          :order_number order-number})]
      payment-id)
    (error "Registration not found" registration-id)))

(defn- handle-payment-success [db payment-params]
  (let [updated (registration-db/complete-registration-and-payment! db payment-params)]
    (if (= updated 1)
  ; (send-confirmation-mail)
      updated)))

(defn- handle-payment-cancelled [db payment-params]
  (info "Payment cancelled" payment-params))

(defn handle-payment-return
  [db {:keys [ORDER_NUMBER PAYMENT_ID AMOUNT TIMESTAMP STATUS PAYMENT_METHOD SETTLEMENT_REFERENCE_NUMBER]}]
  (let [payment-params {:order-number ORDER_NUMBER
                        :payment-id PAYMENT_ID
                        :reference-number (Integer/valueOf SETTLEMENT_REFERENCE_NUMBER)
                        :payment-method PAYMENT_METHOD
                        :timestamp (c/from-long (* 1000 (Long/valueOf TIMESTAMP)))}]
    (case STATUS
      "PAID" (handle-payment-success db payment-params)
      "CANCELLED" (handle-payment-cancelled db payment-params)
      (error "Unknown return status" STATUS))))

