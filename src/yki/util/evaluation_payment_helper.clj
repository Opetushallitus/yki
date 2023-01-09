(ns yki.util.evaluation-payment-helper
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [jeesql.core :refer [require-sql]]
    [yki.util.paytrail-payments :refer [amount->paytrail-amount create-paytrail-payment!]]
    [yki.util.template-util :as template-util]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol EvaluationPaymentHelper
  (order-id->payment-data [this id])
  (insert-initial-payment-data! [this tx payment-data])
  (subtest->price [this subtest]))

(defn- subtest->description
  [url-helper evaluation-order subtest]
  (let [sb          (StringBuilder.)
        {language-code :language_code
         level-code    :level_code
         exam-date     :exam_date
         first-names   :first_names
         last-name     :last_name} evaluation-order
        append-line (fn [& line-items]
                      (.append sb (str/join ", " line-items))
                      (.append sb "\n"))]
    (append-line "Yleinen kielitutkinto (YKI): Tarkistusarviointi")
    ; Exam language, level and subtest
    (append-line
      (template-util/get-language url-helper language-code "fi")
      (template-util/get-level url-helper level-code "fi")
      (template-util/get-subtest url-helper subtest "fi"))
    ; Exam date
    (append-line (str "Tutkintopäivä: " exam-date))
    ; Participant name
    (append-line last-name first-names)
    (.toString sb)))

(defn- subtests->items [payment-helper url-helper evaluation-order-data]
  (->> (for [subtest (:subtests evaluation-order-data)]
         {"unitPrice"     (:paytrail (subtest->price payment-helper subtest))
          "units"         1
          "productCode"   subtest
          "description"   (subtest->description url-helper evaluation-order-data subtest)
          "vatPercentage" 0})
       (into [])))

(defn create-payment-data [payment-helper url-helper evaluation-order-data payment-data]
  (let [{email       :email
         first-names :first_names
         last-name   :last_name} evaluation-order-data
        {order-number :order_number
         amount       :amount
         language     :lang} payment-data
        items         (subtests->items payment-helper url-helper evaluation-order-data)
        callback-urls {"success" (url-helper :evaluation-payment-new.success-callback language)
                       "cancel"  (url-helper :evaluation-payment-new.error-callback language)}]
    {"stamp"        (random-uuid)
     ; Order reference
     "reference"    order-number
     ; Total amount in EUR cents
     "amount"       (amount->paytrail-amount amount)
     "currency"     "EUR"
     "language"     (str/upper-case language)
     "customer"     {"email"     email
                     "firstName" first-names
                     "lastName"  last-name}
     "redirectUrls" callback-urls
     "callbackUrls" callback-urls
     "items"        items}))

(defrecord NewEvaluationPaymentHelper
  [db payment-config url-helper]
  EvaluationPaymentHelper
  (order-id->payment-data [_ id]
    (first (q/select-new-evaluation-payment-by-order-id (:spec db) {:evaluation_order_id id})))
  (insert-initial-payment-data! [this tx payment-data]
    (let [id                      (:evaluation_order_id payment-data)
          evaluation-order-data   (first (q/select-evaluation-order-with-subtests-by-order-id tx {:evaluation_order_id id}))
          paytrail-request-data   (create-payment-data this url-helper evaluation-order-data payment-data)
          paytrail-response       (create-paytrail-payment! payment-config paytrail-request-data)
          response-body           (-> paytrail-response
                                      (:body))
          evaluation-payment-data {:evaluation_order_id id
                                   :amount              (:amount payment-data)
                                   :reference           (paytrail-request-data "reference")
                                   :transaction_id      (response-body "transactionId")
                                   :href                (response-body "href")}]
      (q/insert-initial-evaluation-payment-new<! tx evaluation-payment-data)))
  (subtest->price [_ subtest]
    (let [price (Double/parseDouble (get-in payment-config [:amount (keyword subtest)]))]
      {:email-template (int price)
       :paytrail       (amount->paytrail-amount price)})))

(defmethod ig/init-key :yki.util/evaluation-payment-helper [_ {:keys [db payment-config url-helper]}]
  (->NewEvaluationPaymentHelper db payment-config url-helper))
