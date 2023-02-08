(ns yki.boundary.localisation
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]))

(defn read-translations [lang]
  (let [json (-> (str "yki/localisations/" lang ".json")
              (io/resource)
              (slurp))]
    (json/read-str json)))

(defonce translations {"fi" (read-translations "fi")
                       "en" (read-translations "en")
                       "sv" (read-translations "sv")})

(defn get-translation [lang key]
  (if-let [translation (get-in translations [lang key])]
    translation
    (do
      (log/error "Missing translation for language" lang "and translation" key)
      key)))
