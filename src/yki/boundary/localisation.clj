(ns yki.boundary.localisation
  (:require [yki.util.http-util :as http-util]
            [yki.util.url-helper :as url-helper]
            [clojure.core.memoize :as memo]
            [jsonista.core :as json]))

(defn- get-localisation
  "Converts response returned from localisation service to simple map.
  Response map key format is locale.key and value is the translation."
  [category key url]
  (let [response      (http-util/do-get url (into {} (remove #(nil? (val %)) {:category category
                                                                              :key key})))
        status            (:status response)
        json              (json/read-value (:body response))
        composite-keys    (map #(assoc % :composite-key (str (% "locale") "." (% "key"))) json)
        grouped-by-key    (group-by :composite-key composite-keys)
        flattened-to-map  (reduce-kv #(assoc %1 %2 ((first %3) "value")) {} grouped-by-key)]
    {:json flattened-to-map
     :status status}))

(defonce one-hour (* 1000 60 60))

(def get-localisation-memoized
  (memo/ttl get-localisation :ttl/threshold one-hour))

(defn get-translations [url-helper category key]
  (let [url       (url-helper :localisation.service)
        response  (get-localisation-memoized category key url)]
    (when (= (:status response) 200)
      (:json response))))

(defn get-translation [url-helper key lang]
  (get (get-translations url-helper "yki" key) (str lang "." key)))

