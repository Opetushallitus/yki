(ns yki.boundary.localisation
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log])
  (:import (java.io FileReader)))


(defn read-translations [lang]
  (let [f (-> (str "yki/localisations/" lang ".json")
              (io/resource)
              (io/file))]
    (with-open [rdr (FileReader. f)]
      (json/read rdr))))

(defonce translations {"fi" (read-translations "fi")
                       "en" (read-translations "en")
                       "sv" (read-translations "sv")})

(defn get-translation [lang key]
  (if-let [translation (get-in translations [lang key])]
    translation
    (do
      (log/error "Missing translation for language" lang "and translation" key)
      key)))
