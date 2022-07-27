(ns yki.registration.payment-e2
  (:require [clj-time.coerce :as c]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.boundary.localisation :as localisation]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.registration.payment-e2-util :as payment-util]
            [yki.registration.email :as registration-email]
            [yki.util.common :as common]
            [yki.util.template-util :as template-util]))

(defn handle-legacy-exam-payment-success [db email-q url-helper payment-params]
  (let [participant-data (registration-db/get-participant-data-by-order-number db (:order-number payment-params))
        success          (registration-db/complete-registration-and-legacy-payment! db payment-params)]
    (when success
      (registration-email/send-exam-registration-completed-email! email-q url-helper (:lang participant-data) participant-data))
    success))

(defn handle-evaluation-payment-success [db email-q url-helper payment-config payment-params]
  (let [success       (evaluation-db/complete-payment! db payment-params)
        order-data    (evaluation-db/get-order-data-by-order-number db (:order-number payment-params))
        lang          (:lang order-data)
        order-time    (System/currentTimeMillis)
        template-data (assoc order-data
                        :subject (str (localisation/get-translation url-helper (str "email.evaluation_payment.subject") lang) ":")
                        :language (template-util/get-language url-helper (:language_code order-data) lang)
                        :level (template-util/get-level url-helper (:level_code order-data) lang)
                        :subtests (template-util/get-subtests url-helper (:subtests order-data) lang)
                        :order_time order-time
                        :amount (int (:amount order-data)))]
    (when success
      (info (str "Evaluation payment success, sending email to " (:email order-data) " and Kirjaamo"))

      ;; Customer email
      (registration-email/send-customer-evaluation-registration-completed-email! email-q url-helper lang order-time template-data)

      ;; Kirjaamo email
      (registration-email/send-kirjaamo-evaluation-registration-completed-email! email-q url-helper lang order-time (:email payment-config) template-data))
    success))

(defn- handle-payment-cancelled [_ payment-params]
  (info "Payment cancelled" payment-params))

(defn- number-or-nil [maybe-number]
  (try
    (Integer/valueOf maybe-number)
    (catch Exception _
      nil)))

(defn create-payment-form-data
  [db url-helper payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (if-let [payment (registration-db/get-legacy-payment-by-registration-id db registration-id nil)]
      (let [exam-date                 (common/format-date-string-to-finnish-format (:exam_date registration))
            payment-data              {:language-code lang
                                       :order-number  (:order_number payment)
                                       :msg           (str (localisation/get-translation url-helper "common.paymentMessage" lang) " " exam-date)}
            organizer-specific-config (organizer-db/get-payment-config db (:organizer_id registration))
            test-mode                 (:test_mode organizer-specific-config)
            amount                    (if test-mode "1.00" (str (:amount payment)))
            form-data                 (payment-util/generate-form-data (merge organizer-specific-config payment-config) amount payment-data)]
        form-data)
      (error "Payment not found for registration-id" registration-id))
    (error "Registration with state submitted not found" registration-id)))

(defn create-evaluation-payment-form-data [evaluation-order payment-config url-helper]
  (let [lang         (:lang evaluation-order)
        payment-data {:language-code lang
                      :order-number  (:order_number evaluation-order)
                      :msg           (str (localisation/get-translation url-helper "common.paymentMessage.evaluation" lang))}
        form-data    (payment-util/generate-evaluation-form-data payment-config payment-data url-helper (:subtests evaluation-order))]
    form-data))

(defn valid-return-params? [db params]
  (let [{:keys [merchant_secret]} (registration-db/get-legacy-payment-config-by-order-number db (:ORDER_NUMBER params))]
    (payment-util/valid-return-params? merchant_secret params)))

(defn valid-evaluation-return-params? [params payment-config]
  (payment-util/valid-return-params? (:merchant_secret payment-config) params))

(defn payment-params [{:keys [ORDER_NUMBER PAYMENT_ID TIMESTAMP PAYMENT_METHOD SETTLEMENT_REFERENCE_NUMBER]}]
  {:order-number     ORDER_NUMBER
   :payment-id       PAYMENT_ID
   :reference-number (number-or-nil SETTLEMENT_REFERENCE_NUMBER)
   :payment-method   PAYMENT_METHOD
   :timestamp        (c/from-long (* 1000 (Long/valueOf TIMESTAMP)))})

(defn handle-payment-return
  [db email-q url-helper {:keys [STATUS] :as params}]
  (case STATUS
    "PAID" (handle-legacy-exam-payment-success db email-q url-helper (payment-params params))
    "CANCELLED" (handle-payment-cancelled db (payment-params params))
    (error "Unknown return status" STATUS)))

(defn handle-evaluation-payment-return
  [db email-q url-helper payment-config {:keys [STATUS] :as params}]
  (case STATUS
    "PAID" (handle-evaluation-payment-success db email-q url-helper payment-config (payment-params params))
    "CANCELLED" (handle-payment-cancelled db (payment-params params))
    (error "Unknown return status" STATUS)))

(defn get-payment
  [db {:keys [ORDER_NUMBER]}]
  (registration-db/get-legacy-payment-by-order-number db ORDER_NUMBER))

(defn get-evaluation-payment
  [db {:keys [ORDER_NUMBER]}]
  (evaluation-db/get-payment-by-order-number db ORDER_NUMBER))
