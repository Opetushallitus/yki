(ns yki.util.paytrail-payments
  (:require [clj-time.core :as t]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [yki.util.payments-api :refer [sign-request]]))

(def payments-API-URI "https://services.paytrail.com/payments")

(defn authentication-headers [method merchant-id transaction-id]
  (cond-> {"checkout-account"   merchant-id
           "checkout-algorithm" "sha512"
           "checkout-method"    method
           "checkout-nonce"     (str (random-uuid))
           "checkout-timestamp" (str (t/now))}
          (some? transaction-id)
          (assoc "checkout-transaction-id" transaction-id)))

(defn create-paytrail-payment! [payment-config payment-data]
  (let [authentication-headers (authentication-headers "POST" (:merchant-id payment-config) nil)
        body                   (json/write-str payment-data)
        signature              (sign-request payment-config authentication-headers body)
        headers                (assoc authentication-headers
                                 "content-type" "application/json; charset=utf-8"
                                 "signature" signature)]

    @(http/post
       payments-API-URI
       {:headers headers
        :body    body})))
