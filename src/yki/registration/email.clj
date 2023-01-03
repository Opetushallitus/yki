(ns yki.registration.email
  (:require
    [clojure.set :as set]
    [pgqueue.core :as pgq]
    [yki.util.evaluation-payment-helper :refer [subtest->price]]
    [yki.util.pdf :refer [template+data->pdf-bytes]]
    [yki.util.template-util :as template-util]))

(defn exam-payment-receipt-contents [url-helper pdf-renderer receipt-language registration-data payment-data]
  (let [exam-level    (template-util/get-level url-helper (:level_code registration-data) receipt-language)
        exam-language (template-util/get-language url-helper (:language_code registration-data) receipt-language)
        receipt-data  (->
                        (merge registration-data payment-data)
                        (assoc
                          :receipt_id (:reference payment-data)
                          :receipt_date (:paid_at payment-data)
                          :payment_date (:paid_at payment-data)
                          :level exam-level
                          :language exam-language)
                        (update :amount #(/ % 100))
                        (set/rename-keys {:name :organizer_name}))]
    (template+data->pdf-bytes pdf-renderer "receipt_exam_payment" receipt-language receipt-data)))

(defn evaluation-payment-receipt-contents [payment-helper url-helper pdf-renderer receipt-language template-data]
  (let [template-data (-> (update template-data :subtests
                                  #(map (fn [subtest]
                                          {:price (subtest->price payment-helper subtest)
                                           :name  (template-util/get-subtest url-helper subtest receipt-language)}) %))
                          (set/rename-keys {:first_names :first_name}))]
    (template+data->pdf-bytes pdf-renderer "receipt_evaluation_payment" receipt-language template-data)))

(defn send-exam-registration-completed-email! [email-q url-helper pdf-renderer email-language template-data payment-data]
  (let [exam-level    (template-util/get-level url-helper (:level_code template-data) email-language)
        exam-language (template-util/get-language url-helper (:language_code template-data) email-language)
        receipt-id    (:reference payment-data)]
    (pgq/put email-q
             {:recipients  [(:email template-data)]
              :created     (System/currentTimeMillis)
              :subject     (template-util/subject url-helper "payment_success" email-language template-data)
              :body        (template-util/render url-helper "payment_success" email-language (assoc template-data :language exam-language :level exam-level))
              :attachments (when payment-data
                             [{:name        (str receipt-id ".pdf")
                               :data        (exam-payment-receipt-contents url-helper pdf-renderer email-language template-data payment-data)
                               :contentType "application/pdf"}])})))

(defn send-customer-evaluation-registration-completed-email! [email-q payment-helper url-helper pdf-renderer email-language order-time template-data]
  (let [receipt-id (:order_number template-data)]
    (pgq/put email-q
             {:recipients [(:email template-data)]
              :created    order-time
              :subject    (template-util/evaluation-subject template-data)
              :body       (template-util/render url-helper "evaluation_payment_success" email-language
                                                (update template-data :subtests #(template-util/get-subtests url-helper % email-language)))
              :attachments
              (when payment-helper
                [{:name        (str receipt-id ".pdf")
                  :data        (evaluation-payment-receipt-contents payment-helper url-helper pdf-renderer email-language (assoc template-data :receipt_id receipt-id))
                  :contentType "application/pdf"}])})))

(defn send-kirjaamo-evaluation-registration-completed-email! [email-q url-helper email-language order-time kirjaamo-email template-data]
  (let [template-with-subject (-> (assoc template-data :subject "YKI,")
                                  (update :subtests #(template-util/get-subtests url-helper % "fi")))
        ;; Kirjaamo email will not be translated and only send in Finnish
        kirjaamo-template     (if (= email-language "fi")
                                template-with-subject
                                (assoc template-with-subject
                                  :language (template-util/get-language url-helper (:language_code template-data) "fi")
                                  :level (template-util/get-level url-helper (:level_code template-data) "fi")))]
    (pgq/put email-q
             {:recipients [kirjaamo-email]
              :created    order-time
              :subject    (template-util/evaluation-subject kirjaamo-template)
              :body       (template-util/render url-helper "evaluation_payment_kirjaamo" "fi" kirjaamo-template)})))
