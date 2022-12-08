(ns yki.registration.email
  (:require [yki.util.template-util :as template-util]
            [pgqueue.core :as pgq]
            [clojure.set :as set]
            [yki.util.pdf :refer [template+data->pdf-bytes]]))

(defn- exam-payment-receipt-bytes [url-helper receipt-language registration-data payment-data]
  (let [exam-level    (template-util/get-level url-helper (:level_code registration-data) receipt-language)
        exam-language (template-util/get-language url-helper (:language_code registration-data) receipt-language)
        receipt-data  (->
                        (merge registration-data payment-data)
                        (assoc
                          :receipt_date (:paid_at payment-data)
                          :payment_date (:paid_at payment-data)
                          :level exam-level
                          :language exam-language)
                        (update :amount #(/ % 100))
                        (set/rename-keys {:name :organizer_name}))]
    (template+data->pdf-bytes url-helper "receipt_exam_payment" "fi" receipt-data)))

(defn send-exam-registration-completed-email! [email-q url-helper email-language template-data payment-data]
  (let [exam-level    (template-util/get-level url-helper (:level_code template-data) email-language)
        exam-language (template-util/get-language url-helper (:language_code template-data) email-language)
        receipt-id    (str "YKI-EXAM-" (:id payment-data))]
    (pgq/put email-q
             {:recipients  [(:email template-data)]
              :created     (System/currentTimeMillis)
              :subject     (template-util/subject url-helper "payment_success" email-language template-data)
              :body        (template-util/render url-helper "payment_success" email-language (assoc template-data :language exam-language :level exam-level))
              :attachments (when payment-data
                             [{:name        (str "kuitti_" receipt-id ".pdf")
                               :data        (exam-payment-receipt-bytes url-helper "fi" template-data (assoc payment-data :receipt_id receipt-id))
                               :contentType "application/pdf"}])})))

(defn send-customer-evaluation-registration-completed-email! [email-q url-helper email-language order-time template-data]
  (pgq/put email-q
           {:recipients [(:email template-data)]
            :created    order-time
            :subject    (template-util/evaluation-subject template-data)
            :body       (template-util/render url-helper "evaluation_payment_success" email-language template-data)}))

(defn send-kirjaamo-evaluation-registration-completed-email! [email-q url-helper email-language order-time kirjaamo-email template-data]
  (let [template-with-subject (assoc template-data :subject "YKI,")
        ;; Kirjaamo email will not be translated and only send in Finnish
        kirjaamo-template     (if (= email-language "fi")
                                template-with-subject
                                (assoc template-with-subject
                                  :language (template-util/get-language url-helper (:language_code template-data) "fi")
                                  :level (template-util/get-level url-helper (:level_code template-data) "fi")
                                  :subtests (template-util/get-subtests url-helper (:subtests template-data) "fi")))]
    (pgq/put email-q
             {:recipients [kirjaamo-email]
              :created    order-time
              :subject    (template-util/evaluation-subject kirjaamo-template)
              :body       (template-util/render url-helper "evaluation_payment_kirjaamo" "fi" kirjaamo-template)})))
