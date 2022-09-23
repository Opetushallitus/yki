(ns yki.util.payments-api
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.mac :as mac]
    [clojure.string :as str])
  (:import (java.io InputStream ByteArrayOutputStream)))

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

(defn- request->body [{:keys [body]}]
  (if (instance? InputStream body)
    (with-open [baos (ByteArrayOutputStream.)]
      (.transferTo ^InputStream body baos)
      (.toString baos))
    body))

(defn valid-request?
  "Checks whether the request signature matches the request body and authentication headers.
   Handles authentication headers passed as both actual HTTP headers or as part of query parameters.

   If signature is valid, returns a map containing the (now exhausted) request body as a string.
   Returns nil if signature is invalid."
  [payment-config request]
  (let [{headers      :headers
         query-params :query-params} request
        authentication-headers (merge headers query-params)
        body                   (request->body request)]
    (when
      (= (sign-request payment-config authentication-headers body)
         (authentication-headers "signature"))
      {:authentication-headers authentication-headers
       :body                   body})))
