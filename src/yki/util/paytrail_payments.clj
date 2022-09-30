(ns yki.util.paytrail-payments
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.mac :as mac]
    [clj-time.core :as t]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [org.httpkit.client :as http]))

(def payments-API-URI "https://services.paytrail.com/payments")

(defn authentication-headers [method merchant-id transaction-id]
  (cond-> {"checkout-account"   merchant-id
           "checkout-algorithm" "sha512"
           "checkout-method"    method
           "checkout-nonce"     (str (random-uuid))
           "checkout-timestamp" (str (t/now))}
          (some? transaction-id)
          (assoc "checkout-transaction-id" transaction-id)))

(defn- headers->signature-keys-order [headers]
  (->> (keys headers)
       (filter #(str/starts-with? % "checkout-"))
       (sort)))

(defn sign-string [{:keys [merchant-secret]} data]
  {:pre [(string? merchant-secret) (string? data)]}
  (-> data
      (mac/hash {:key merchant-secret
                 :alg :hmac+sha512})
      (codecs/bytes->hex)))

(defn sign-request [payment-config headers body]
  (let [sb (StringBuilder.)]
    (doseq [header (headers->signature-keys-order headers)]
      (when-let [data (headers header)]
        (.append sb header)
        (.append sb ":")
        (.append sb data)
        (.append sb "\n")))
    (when body
      (.append sb body))
    (sign-string payment-config (.toString sb))))

(defn amount->paytrail-amount [price-in-eur]
  (int (* 100 price-in-eur)))

(defn create-paytrail-payment! [payment-config payment-data]
  (let [authentication-headers (authentication-headers "POST" (:merchant-id payment-config) nil)
        body                   (json/write-str payment-data)
        signature              (sign-request payment-config authentication-headers body)
        headers                (assoc authentication-headers
                                 "content-type" "application/json; charset=utf-8"
                                 "signature" signature)
        response               @(http/post
                                  payments-API-URI
                                  {:headers headers
                                   :body    body})
        response-status        (:status response)
        response-body          (-> response
                                   (:body)
                                   (json/read-str))]
    (if (<= 200 response-status 299)
      {:status response-status
       :body   response-body}
      (do
        (log/error "Caught unexpected HTTP return code from Paytrail API. Status:" response-status ", response body:" response-body)
        (throw (ex-info "Unexpected status code returned from Paytrail." {:status response-status}))))))
