(ns yki.registration.paytrail-payment
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [yki.util.common :as common]
            [clojure.string :as str]
            [clj-time.coerce :as c]
            [pgqueue.core :as pgq]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.localisation :as localisation]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.registration.payment-util :as payment-util]
            [ring.util.http-response :refer [not-found]]
            [clojure.tools.logging :refer [info error]]
            [integrant.core :as ig]))

(defn handle-payment-success [db email-q url-helper payment-params]
  (let [participant-data (registration-db/get-participant-data-by-order-number db (:order-number payment-params))
        lang (:lang participant-data)
        success (registration-db/complete-registration-and-payment! db payment-params)]
    (when success
      (pgq/put email-q
               {:recipients [(:email participant-data)],
                :created (System/currentTimeMillis)
                :subject (template-util/subject url-helper "payment_success" lang participant-data)
                :body (template-util/render url-helper "payment_success" lang participant-data)}))
    success))

(defn- handle-payment-cancelled [db payment-params]
  (info "Payment cancelled" payment-params))

(defn- number-or-nil [maybe-number]
  (try
    (Integer/valueOf maybe-number)
    (catch Exception e
      nil)))

(defn create-payment-form-data
  [db url-helper payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (if-let [payment (registration-db/get-payment-by-registration-id db registration-id nil)]
      (let [amount (str (:amount payment))
            exam-date (common/format-date-string-to-finnish-format (:exam_date registration))
            payment-data {:language-code lang
                          :order-number (:order_number payment)
                          :msg (str (localisation/get-translation url-helper "common.paymentMessage" lang) " " exam-date)}
            organizer-specific-config (organizer-db/get-payment-config db (:organizer_id registration))
            form-data (payment-util/generate-form-data (merge organizer-specific-config payment-config) amount payment-data)]
        form-data)
      (error "Payment not found for registration-id" registration-id))
    (error "Registration not found" registration-id)))

(defn valid-return-params? [db params]
  (let [{:keys [merchant_secret]} (registration-db/get-payment-config-by-order-number db (:ORDER_NUMBER params))]
    (payment-util/valid-return-params? merchant_secret params)))

(defn handle-payment-return
  [db email-q url-helper {:keys [ORDER_NUMBER PAYMENT_ID AMOUNT TIMESTAMP STATUS PAYMENT_METHOD SETTLEMENT_REFERENCE_NUMBER]}]
  (let [payment-params {:order-number ORDER_NUMBER
                        :payment-id PAYMENT_ID
                        :reference-number (number-or-nil SETTLEMENT_REFERENCE_NUMBER)
                        :payment-method PAYMENT_METHOD
                        :timestamp (c/from-long (* 1000 (Long/valueOf TIMESTAMP)))}]
    (case STATUS
      "PAID" (handle-payment-success db email-q url-helper payment-params)
      "CANCELLED" (handle-payment-cancelled db payment-params)
      (error "Unknown return status" STATUS))))

(defn get-payment
  [db {:keys [ORDER_NUMBER]}]
  (registration-db/get-payment-by-order-number db ORDER_NUMBER))

