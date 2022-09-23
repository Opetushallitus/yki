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
  (use-new-payments-api? [this]))

(defrecord OldEvaluationPaymentHelper
  [db payment-config]
  EvaluationPaymentHelper
  (order-id->payment-data [_ id]
    (first (q/select-old-evaluation-payment-by-order-id (:spec db) {:evaluation_order_id id})))
  (insert-initial-payment-data! [_ tx payment-data]
    (q/insert-initial-evaluation-payment<! tx payment-data))
  (use-new-payments-api? [_]
    false))

(defn- subtests->items [url-helper payment-config lang subtests]
  (let [subtest->amount (-> (:amount payment-config)
                            (update-keys name)
                            (update-vals #(amount->paytrail-amount (Double/parseDouble %))))]
    (for [subtest subtests]
      {"unitPrice"     (subtest->amount subtest)
       "units"         1
       "productCode"   subtest
       "description"   (template-util/get-subtest url-helper subtest lang)
       "vatPercentage" 0})))

(defn create-payment-data [url-helper payment-config evaluation-order-data payment-data]
  (let [{email       :email
         first-names :first_names
         last-name   :last_name
         subtests    :subtests} evaluation-order-data
        {order-number :order_number
         amount       :amount
         language     :lang} payment-data
        items         (subtests->items url-helper payment-config language subtests)
        callback-urls {"success" (url-helper :evaluation-payment-new.success-callback language)
                       "cancel"  (url-helper :evaluation-payment-new.error-callback language)}]
    {"stamp"        (random-uuid)
     ; Order reference
     "reference"    order-number
     ; Total amount in EUR cents
     "amount"       (int (* 100 amount))
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
  (insert-initial-payment-data! [_ tx payment-data]
    (let [id                      (:evaluation_order_id payment-data)
          evaluation-order-data   (first (q/select-evaluation-order-with-subtests-by-order-id tx {:evaluation_order_id id}))
          paytrail-request-data   (create-payment-data url-helper payment-config evaluation-order-data payment-data)
          paytrail-response       (create-paytrail-payment! payment-config paytrail-request-data)
          response-body           (-> paytrail-response
                                      (:body))
          evaluation-payment-data {:evaluation_order_id id
                                   :amount              (:amount payment-data)
                                   :reference           (paytrail-request-data "reference")
                                   :transaction_id      (response-body "transactionId")
                                   :href                (response-body "href")}]
      (q/insert-initial-evaluation-payment-new<! tx evaluation-payment-data)))
  (use-new-payments-api? [_]
    true))

(defmethod ig/init-key :yki.util/evaluation-payment-helper [_ {:keys [db payment-config url-helper]}]
  (if (:use-new-payments-api? payment-config)
    (->NewEvaluationPaymentHelper db payment-config url-helper)
    (->OldEvaluationPaymentHelper db payment-config)))

