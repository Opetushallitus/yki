(ns yki.registration.paytrail-payment
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [yki.util.common :as common]
            [clojure.string :as str]
            [clj-time.coerce :as c]
            [pgqueue.core :as pgq]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.boundary.localisation :as localisation]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.registration.payment-util :as payment-util]
            [ring.util.http-response :refer [not-found]]
            [clojure.tools.logging :refer [info error]]
            [integrant.core :as ig]))

(defn handle-payment-success [db email-q url-helper payment-params]
  (let [participant-data (registration-db/get-participant-data-by-order-number db (:order-number payment-params))
        lang (:lang participant-data)
        level (template-util/get-level url-helper (:level_code participant-data) lang)
        language (template-util/get-language url-helper (:language_code participant-data) lang)
        success (registration-db/complete-registration-and-payment! db payment-params)]
    (when success
      (pgq/put email-q
               {:recipients [(:email participant-data)]
                :created (System/currentTimeMillis)
                :subject (template-util/subject url-helper "payment_success" lang participant-data)
                :body (template-util/render url-helper "payment_success" lang (assoc participant-data :language language :level level))}))
    success))

;; TODO Add email sending to the customer and to Kirjaamo
(defn handle-evaluation-payment-success [db email-q url-helper payment-params]
  (let [success (evaluation-db/complete-payment! db payment-params)]
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
      (let [exam-date (common/format-date-string-to-finnish-format (:exam_date registration))
            payment-data {:language-code lang
                          :order-number (:order_number payment)
                          :msg (str (localisation/get-translation url-helper "common.paymentMessage" lang) " " exam-date)}
            organizer-specific-config (organizer-db/get-payment-config db (:organizer_id registration))
            test-mode (:test_mode organizer-specific-config)
            amount (if test-mode "1.00" (str (:amount payment)))
            form-data (payment-util/generate-form-data (merge organizer-specific-config payment-config) amount payment-data)]
        form-data)
      (error "Payment not found for registration-id" registration-id))
    (error "Registration with state submitted not found" registration-id)))

(defn create-evaluation-payment-form-data
  [url-helper evaluation-order payment-config]
  (let [lang         (:lang evaluation-order)
        payment-data {:language-code lang
                      :order-number  (:order_number evaluation-order)
                      :msg (str (localisation/get-translation url-helper "common.paymentMessage" lang))}
        amount        (if (:test_mode payment-config) "1.00" (:amount evaluation-order))
        form-data     (payment-util/generate-form-data payment-config amount payment-data)]
    form-data))

(defn valid-return-params? [db params]
  (let [{:keys [merchant_secret]} (registration-db/get-payment-config-by-order-number db (:ORDER_NUMBER params))]
    (payment-util/valid-return-params? merchant_secret params)))

(defn valid-evaluation-return-params? [params payment-config]
  (payment-util/valid-return-params? (:merchant_secret payment-config) params))

(defn payment-params [{:keys [ORDER_NUMBER PAYMENT_ID TIMESTAMP PAYMENT_METHOD SETTLEMENT_REFERENCE_NUMBER]}]
  {:order-number ORDER_NUMBER
   :payment-id PAYMENT_ID
   :reference-number (number-or-nil SETTLEMENT_REFERENCE_NUMBER)
   :payment-method PAYMENT_METHOD
   :timestamp (c/from-long (* 1000 (Long/valueOf TIMESTAMP)))})

(defn handle-payment-return
  [db email-q url-helper {:keys [STATUS] :as params}]
  (case STATUS
    "PAID" (handle-payment-success db email-q url-helper (payment-params params))
    "CANCELLED" (handle-payment-cancelled db (payment-params params))
    (error "Unknown return status" STATUS)))

(defn handle-evaluation-payment-return
  [db email-q url-helper {:keys [STATUS] :as params}]
  (case STATUS
    "PAID" (handle-evaluation-payment-success db email-q url-helper (payment-params params))
    "CANCELLED" (handle-payment-cancelled db (payment-params params))
    (error "Unknown return status" STATUS)))

(defn get-payment
  [db {:keys [ORDER_NUMBER]}]
  (registration-db/get-payment-by-order-number db ORDER_NUMBER))

(defn get-evaluation-payment
  [db {:keys [ORDER_NUMBER]}]
  (evaluation-db/get-payment-by-order-number db ORDER_NUMBER))


