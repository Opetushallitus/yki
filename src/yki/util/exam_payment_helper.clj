(ns yki.util.exam-payment-helper
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [jeesql.core :refer [require-sql]]
    [yki.util.paytrail-payments :refer [create-paytrail-payment!]]
    [yki.util.template-util :as template-util]))

(require-sql ["yki/queries.sql" :as q])

(defn- registration->payment-amount [payment-config registration-details]
  (let [level-code (keyword (:level_code registration-details))]
    (->> [:amount level-code]
         (get-in payment-config)
         (bigdec))))

(defprotocol PaymentHelper
  (get-payment-redirect-url [this registration-id lang])
  (get-payment-amount-for-registration [this registration-details])
  (create-or-return-payment-for-registration! [this tx registration language amount])
  (initialise-payment-on-registration? [this]))

(defrecord LegacyPaymentHelper [db url-helper payment-config]
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.old.redirect registration-id lang))
  (get-payment-amount-for-registration [_ registration-details]
    (let [amount (registration->payment-amount payment-config registration-details)]
      {:email-template amount
       :paytrail       amount}))
  (create-or-return-payment-for-registration! [_ tx registration language amount]
    (let [order-number-seq (:nextval (first (q/select-next-order-number-suffix tx)))
          oid-last-part    (last (str/split (:oid registration) #"\."))
          order-number     (str "YKI" oid-last-part (format "%09d" order-number-seq))
          payment          {:registration_id (:id registration)
                            :amount          amount
                            :lang            language
                            :order_number    order-number}]
      (q/insert-legacy-payment<! tx payment)))
  (initialise-payment-on-registration? [_]
    true))

(defn- registration->payment-description
  [url-helper registration]
  (let [sb          (StringBuilder.)
        {language-code           :language_code
         level-code              :level_code
         location-name           :name
         exam-date               :exam_date
         {first-name :first_name
          last-name  :last_name} :form} registration
        append-line (fn [& line-items]
                      (.append sb (str/join ", " line-items))
                      (.append sb "\n"))]
    (append-line "Yleinen kielitutkinto (YKI): Tutkintomaksu")
    ; Exam language and level
    (append-line
      (template-util/get-language url-helper language-code "fi")
      (template-util/get-level url-helper level-code "fi"))
    ; Exam location and date
    (append-line location-name exam-date)
    ; Participant name
    (append-line last-name first-name)
    (.toString sb)))

(defn create-payment-data [url-helper registration language amount]
  (let [{registration-id   :id
         exam-session-id   :exam_session_id
         organizer-id      :organizer_id
         email             :email
         registration-form :form} registration
        callback-urls {"success" (url-helper :exam-payment-new.success-callback language)
                       "cancel"  (url-helper :exam-payment-new.error-callback language)}]
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
                     "firstName" (:first_name registration-form)
                     "lastName"  (:last_name registration-form)}
     "redirectUrls" callback-urls
     "callbackUrls" callback-urls
     "items"        [{"unitPrice"     amount
                      "units"         1
                      "vatPercentage" 0
                      "productCode"   (str exam-session-id)
                      "description"   (registration->payment-description url-helper registration)}]}))

(defrecord NewPaymentHelper [db url-helper payment-config]
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.new.redirect registration-id lang))
  (get-payment-amount-for-registration [_ registration-details]
    (let [amount (registration->payment-amount payment-config registration-details)]
      {:email-template amount
       ; Unit of returned amount is EUR.
       ; Return corresponding amount in minor unit, ie. cents.
       :paytrail       (* 100 (int amount))}))
  (create-or-return-payment-for-registration! [_ tx registration language amount]
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
        response-body)))
  (initialise-payment-on-registration? [_]
    false))

(defmethod ig/init-key :yki.util/exam-payment-helper [_ {:keys [db url-helper payment-config]}]
  (if (:use-new-payments-api? payment-config)
    (->NewPaymentHelper db url-helper payment-config)
    (->LegacyPaymentHelper db url-helper payment-config)))
