(ns yki.util.template-util
  (:require   [selmer.parser :as selmer]
              [clojure.string :as str]
              [clojure.tools.logging :refer [info]]))

(defn- template-name
  [base lang]
  (str (str/lower-case base) "_" lang ".html"))

(def subject-by-lang
  {"fi" {"LOGIN_LINK"  "Ilmoittautuminen"
         "payment_success" "Ilmoittautuminen maksettu"},
   "sv" {"LOGIN_LINK"  "Ilmoittautuminen_sv"
         "payment_success" "Ilmoittautuminen maksettu_sv"},
   "en" {"LOGIN_LINK"  "Ilmoittautuminen_en"
         "payment_success" "Ilmoittautuminen maksettu_en"}})

(defn subject
  [template lang]
  (get-in subject-by-lang [lang template]))

(defn render
  [template lang params]
  (selmer/render-file (str "yki/templates/" (template-name template lang)) params))
