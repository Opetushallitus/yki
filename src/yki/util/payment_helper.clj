(ns yki.util.payment-helper
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [jeesql.core :refer [require-sql]]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol PaymentHelper
  (get-payment-redirect-url [this registration-id lang])
  (get-payment [this registration-id oid])
  (get-payment-by-order-number [this order-number])
  (create-payment-for-registration! [this tx registration language amount])
  (initialise-payment-on-registration? [this]))

(defrecord LegacyPaymentHelper [db url-helper payment-config]
  ; TODO payment-config might not be needed?
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.old.redirect registration-id lang))
  (get-payment [_ registration-id oid]
    (first (q/select-payment-by-registration-id
             (:spec db)
             {:registration_id registration-id :oid oid})))
  (get-payment-by-order-number [_ order-number]
    (first (q/select-payment-by-order-number (:spec db) {:order_number order-number})))
  (create-payment-for-registration! [_ tx registration language amount]
    (let [order-number-seq (:nextval (first (q/select-next-order-number-suffix tx)))
          oid-last-part    (last (str/split (:oid registration) #"\."))
          order-number     (str "YKI" oid-last-part (format "%09d" order-number-seq))
          payment          {:registration_id (:id registration)
                            :amount          amount
                            :lang            language
                            :order_number    order-number}
          ]
      (q/insert-legacy-payment<! tx payment)))
  (initialise-payment-on-registration? [_]
    true)
  )

(defrecord NewPaymentHelper [db url-helper]
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.new.redirect registration-id lang))
  (get-payment [_ registration-id oid]
    ; TODO
    nil)
  (get-payment-by-order-number [_ order-number]
    ; TODO
    nil)
  (initialise-payment-on-registration? [this]
    false))

(defmethod ig/init-key :yki.util/payment-helper [_ {:keys [db url-helper payment-config]}]
  (if (:use-new-payments-api? payment-config)
    (->NewPaymentHelper db url-helper)
    (->LegacyPaymentHelper db url-helper payment-config)))
