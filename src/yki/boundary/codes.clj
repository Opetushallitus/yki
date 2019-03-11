(ns yki.boundary.codes
  (:require [yki.util.http-util :as http-util]
            [yki.util.url-helper :as url-helper]
            [clojure.core.memoize :as memo]
            [jsonista.core :as j]))

(defn- get-country-code
  "Converts codes from maatjavaltiot2 to maatjavaltiot1 (246 -> FIN)."
  [url]
  (let [response (http-util/do-get url {})
        status   (:status response)
        json     (j/read-value (:body response) (j/object-mapper {:decode-key-fn true}))]
    (if (= (:status response) 200)
      (:koodiArvo (some #(if (= (get-in % [:koodisto :koodistoUri]) "maatjavaltiot1") %) json))
      (throw (RuntimeException. (str "Could not get country code from url " url))))))

(defn- get-codes-from-koodisto
  [url-helper collection]
  (let [url (url-helper :koodisto-service collection 1)
        response (http-util/do-get url {})
        status   (:status response)
        json     (j/read-value (:body response) (j/object-mapper {:decode-key-fn true}))]
    (if (= (:status response) 200)
      json
      (throw (RuntimeException. (str "Could not get codes from url " url))))))

(defonce one-week (* 1000 60 60 24 7))

(def get-country-code-memoized
  (memo/ttl get-country-code :ttl/threshold one-week))

(def get-codes-memoized
  (memo/ttl get-codes-from-koodisto :ttl/threshold one-week))

(defn get-converted-country-code [url-helper country-code]
  (let [url       (url-helper :koodisto-service.rinnasteinen (str "maatjavaltiot2_" country-code) 1)
        response  (get-country-code-memoized url)]
    response))

(defn get-codes [url-helper collection]
  (get-codes-memoized url-helper collection))

(defn get-code [url-helper collection code]
  (let [codes (get-codes-memoized url-helper collection)
        code-value (first (filter #(= (:koodiArvo %) code) codes))]
    code-value))

