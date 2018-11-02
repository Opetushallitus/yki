(ns yki.payment.paytrail-payment
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [clojure.string :as str]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.payment-db :as payment-db]
            [yki.payment.payment-util :as payment-util]
            [ring.util.http-response :refer [not-found]]
            [clojure.tools.logging :refer [error]]
            [integrant.core :as ig]))

(defn- create-order-number [db reference-number]
  (let [order-number-suffix (payment-db/get-next-order-number-suffix! db)]
    (str "YKI" reference-number order-number-suffix)))

(defn- create-reference-number [external-id]
  (let [with-prefix (str "900" external-id)
        reference-number-with-checknum (s/conform ::ys/reference-number-conformer with-prefix)]
    (when-not (s/invalid? reference-number-with-checknum)
      reference-number-with-checknum)))

(defn create-payment-form-data
  [db payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (if-let [payment (payment-db/get-payment-by-registration-id db registration-id)]
      (let [payment-data {:language-code lang
                          :order-number (:order_number payment)
                          :reference-number (:reference_number payment)}
            form-data (payment-util/generate-form-data payment-config payment-data)]
        form-data)
      (error "Payment not found for registration-id" registration-id))
    (error "Registration not found" registration-id)))

(defn valid-return-params? [payment-config params]
  (payment-util/valid-return-params? payment-config params))

(defn create-payment
  [db payment-config registration-id external-user-id lang]
  (if-let [registration (registration-db/get-registration db registration-id external-user-id)]
    (let [reference-number (-> (str/split external-user-id #"\.") last create-reference-number)
          order-number (create-order-number db reference-number)
          payment-id (payment-db/create-payment! db {:registration_id registration-id
                                                     :amount (payment-config :amount)
                                                     :reference_number reference-number
                                                     :order_number order-number})]
      payment-id)
    (error "Registration not found" registration-id)))

