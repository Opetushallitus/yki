(ns yki.util.template-util
  (:require [selmer.parser :as parser]
            [selmer.filters :as filters]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info]]))

(defn- template-name
  [base lang]
  (str (str/lower-case base) "_" lang ".html"))

(def date-formatter (f/formatter "d.M.YYYY"))

; (filters/add-tag! :i18n
;                   (fn [[k] context]
;                     ("implement_me")))

(filters/add-filter! :date-format-with-dots
                     (fn [date-string]
                       (f/unparse date-formatter (f/parse date-string))))

(filters/add-filter! :replace-dot-with-comma
                     #(str/replace % "." ","))
(def subject-by-lang
  {"fi" {"LOGIN_LINK"  "Ilmoittautuminen"
         "PAYMENT" "Maksulinkki"
         "payment_success" "Ilmoittautuminen maksettu"},
   "sv" {"LOGIN_LINK"  "Ilmoittautuminen_sv"
         "PAYMENT" "Maksulinkki_sv"
         "payment_success" "Ilmoittautuminen maksettu_sv"},
   "en" {"LOGIN_LINK"  "Ilmoittautuminen_en"
         "PAYMENT" "Maksulinkki"
         "payment_success" "Ilmoittautuminen maksettu_en"}})

(defn subject
  [template lang]
  (get-in subject-by-lang [lang template]))

(defn render
  [template lang params]
  (parser/render-file (str "yki/templates/" (template-name template lang)) params))
