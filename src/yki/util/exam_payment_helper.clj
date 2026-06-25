(ns yki.util.exam-payment-helper
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [jeesql.core :refer [require-sql]]
    [yki.util.paytrail-payments :refer [create-paytrail-payment!]]
    [yki.util.template-util :as template-util]))

(require-sql ["yki/queries.sql" :as q])

(defn- registration->payment-amount [payment-config registration-details]
  (-> (or
        (:exam_fee registration-details)
        (let [code-from-config (fn [level-code]
                                 (->> [:amount level-code]
                                      (get-in payment-config)))
              level-code (case (:exam_type registration-details)
                          "READ_SPEAK" (case (:registration_type registration-details)
                                         "ALL_PARTS" [:KESKI_READ :KESKI_SPEAK]
                                         "READ" [:KESKI_READ]
                                         "SPEAK" [:KESKI_SPEAK])
                          "LISTEN_WRITE" (case (:registration_type registration-details)
                                           "ALL_PARTS" [:KESKI_LISTEN :KESKI_WRITE]
                                           "LISTEN" [:KESKI_LISTEN]
                                           "WRITE" [:KESKI_WRITE])
                          [(keyword (:level_code registration-details))])]
          (reduce + (map code-from-config level-code))))
      (bigdec)))

(defprotocol PaymentHelper
  (get-payment-amount-for-registration [this registration-details])
  (registration->payment [this tx registration language amount])
  (subtest-prices [this registration]))

(defn- registration->payment-description
  [registration]
  (let [sb          (StringBuilder.)
        {language-code :language_code
         level-code    :level_code
         location-name :name
         exam-date     :exam_date
         first-name    :first_name
         last-name     :last_name} registration
        append-line (fn [& line-items]
                      (.append sb (str/join ", " line-items))
                      (.append sb "\n"))]
    (append-line "Yleinen kielitutkinto (YKI): Tutkintomaksu")
    ; Exam language and level
    (append-line
      (template-util/get-language language-code "fi")
      (template-util/get-level level-code "fi"))
    ; Exam location and date
    (append-line location-name exam-date)
    ; Participant name
    (append-line last-name first-name)
    (.toString sb)))

(defn create-payment-data [url-helper registration language amount]
  (let [{registration-id :id
         exam-session-id :exam_session_id
         organizer-id    :organizer_id
         email           :email
         first-name      :first_name
         last-name       :last_name} registration
        callback-urls {"success" (url-helper :exam-payment-v3.success-callback language)
                       "cancel"  (url-helper :exam-payment-v3.error-callback language)}]
    {"stamp"        (random-uuid)
     ; Order reference
     "reference"    (str/join "-"
                              ["YKI"
                               "EXAM"
                               organizer-id
                               exam-session-id
                               registration-id
                               (random-uuid)])
     ; Total amount in EUR cents
     "amount"       amount
     "currency"     "EUR"
     "language"     (str/upper-case language)
     "customer"     {"email"     email
                     "firstName" first-name
                     "lastName"  last-name}
     "redirectUrls" callback-urls
     "callbackUrls" callback-urls
     "items"        [{"unitPrice"     amount
                      "units"         1
                      "vatPercentage" 0
                      "productCode"   (str exam-session-id)
                      "description"   (registration->payment-description registration)}]}))

(defrecord NewPaymentHelper [db url-helper payment-config]
  PaymentHelper
  (get-payment-amount-for-registration [_ registration-details]
    (let [amount (registration->payment-amount payment-config registration-details)]
      {:db             amount
       :email-template amount
       ; Unit of returned amount is EUR.
       ; Return corresponding amount in minor unit, ie. cents.
       :paytrail       (* 100 (int amount))}))
  (subtest-prices [_ registration]
    (let [{:keys [exam_type registration_type]} registration
          price-for (fn [k] {:email-template (get-in payment-config [:amount k])})]
      (when exam_type
        (case exam_type
          "READ_SPEAK"
          (case registration_type
            "ALL_PARTS" [{:subtest "READING"  :price (price-for :KESKI_READ)}
                         {:subtest "SPEAKING" :price (price-for :KESKI_SPEAK)}]
            "READ"      [{:subtest "READING"  :price (price-for :KESKI_READ)}]
            "SPEAK"     [{:subtest "SPEAKING" :price (price-for :KESKI_SPEAK)}]
            nil)
          "LISTEN_WRITE"
          (case registration_type
            "ALL_PARTS" [{:subtest "LISTENING" :price (price-for :KESKI_LISTEN)}
                         {:subtest "WRITING"   :price (price-for :KESKI_WRITE)}]
            "LISTEN"    [{:subtest "LISTENING" :price (price-for :KESKI_LISTEN)}]
            "WRITE"     [{:subtest "WRITING"   :price (price-for :KESKI_WRITE)}]
            nil)
          nil))))
  (registration->payment [_ tx registration language amount]
    (if-let [existing-payment-redirect-url (->> (q/select-unpaid-new-exam-payments-by-registration-id tx {:registration_id (:id registration)})
                                                (first)
                                                (:href))]
      {"href" existing-payment-redirect-url}
      (let [payment-data      (create-payment-data url-helper registration language amount)
            paytrail-response (create-paytrail-payment! payment-config payment-data)
            response-body     (:body paytrail-response)
            exam-payment-data {:registration_id (:id registration)
                               :amount          amount
                               :reference       (payment-data "reference")
                               :transaction_id  (response-body "transactionId")
                               :href            (response-body "href")}]
        (q/insert-new-exam-payment<! tx exam-payment-data)
        response-body))))

(defmethod ig/init-key :yki.util/exam-payment-helper [_ {:keys [db url-helper payment-config]}]
  (->NewPaymentHelper db url-helper payment-config))
