(ns yki.util.payments-api
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.mac :as mac]
    [clojure.string :as str])
  (:import (java.io InputStream ByteArrayOutputStream)))

(def test-merchant-secret-key "SAIPPUAKAUPPIAS")

(defn- headers->signature-keys-order [headers]
  (->> (keys headers)
       (filter #(str/starts-with? % "checkout-"))
       (sort)))

(defn sign-request [headers body]
  (let [sb (StringBuilder.)]
    (doseq [header (headers->signature-keys-order headers)]
      (when-let [data (headers header)]
        (.append sb header)
        (.append sb ":")
        (.append sb data)
        (.append sb "\n")))
    (when body
      (.append sb body))
    (-> (.toString sb)
        (mac/hash {:key test-merchant-secret-key
                   :alg :hmac+sha512})
        (codecs/bytes->hex))))


(defn- request->body [{:keys [body]}]
  (if (instance? InputStream body)
    (with-open [baos (ByteArrayOutputStream.)]
      (.transferTo ^InputStream body baos)
      (.toString baos))
    body))

(defn valid-request? [{:keys [headers query-params]
                       :as   request}]
  (let [authentication-headers (merge headers query-params)]
    (= (sign-request authentication-headers (request->body request))
       (authentication-headers "signature"))))
