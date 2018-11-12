(ns yki.util.template-util
  (:require   [selmer.parser :as selmer]
              [clojure.tools.logging :refer [info]]))

(defn- template-name
  [base lang]
  (str base "_" lang ".html"))

(def subject-by-lang
  {"fi" {"login_link"  "Ilmoittautuminen"},
   "sv" {"login_link"  "Ilmoittautuminen_sv"},
   "en" {"login_link"  "Ilmoittautuminen_en"}})

(defn subject
  [template lang]
  (get-in subject-by-lang [lang template]))

(defn render
  [template lang params]
  (selmer/render-file (str "yki/templates/" (template-name template lang)) params))
