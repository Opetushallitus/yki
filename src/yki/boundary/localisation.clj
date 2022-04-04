(ns yki.boundary.localisation
  (:require [clojure.core.memoize :as memo]
            [jsonista.core :as json]
            [yki.util.http-util :as http-util]))

(defn- get-localisation
  "Converts response returned from localisation service to simple map."
  [category key url lang]
  (let [response         (http-util/do-get url (into {} (remove #(nil? (val %)) {:category category
                                                                                 :locale   lang
                                                                                 :key      key})))
        status           (:status response)
        json             (json/read-value (:body response) (json/object-mapper {:decode-key-fn true}))
        grouped-by-key   (group-by :key json)
        flattened-to-map (reduce-kv #(assoc %1 %2 ((first %3) :value)) {} grouped-by-key)]
    {:json   flattened-to-map
     :status status}))

(defonce one-hour (* 1000 60 60))

(def get-localisation-memoized
  (memo/ttl get-localisation :ttl/threshold one-hour))

(defn get-translations [url-helper category key lang]
  (let [url      (url-helper :localisation.service)
        response (get-localisation-memoized category key url lang)]
    (when (= (:status response) 200)
      (:json response))))

(defn get-translation [url-helper key lang]
  (get (get-translations url-helper "yki" key lang) key))
