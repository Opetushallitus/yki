(ns yki.util.template-util
  (:require
   [yki.boundary.localisation :as localisation]
   [yki.util.common :as common]
   [integrant.core :as ig]
   [selmer.parser :as parser]
   [selmer.filters :as filters]
   [clj-time.format :as f]
   [clj-time.core :as t]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [clojure.tools.logging :refer [info]]))

(defn- template-name [name]
  (str (str/lower-case name) ".html"))

(def level-translation-key
  {"PERUS" "common.level.basic"
   "KESKI" "common.level.middle"
   "YLIN" "common.level.high"})

(def subtest-translation-key
  {"WRITING" "registration.description.write"
   "LISTENING" "registration.description.listen"
   "READING" "registration.description.read"
   "SPEAKING" "registration.description.speak"})

(parser/cache-off!)

(parser/add-tag! :i18n
                 (fn [[key] context]
                   (let [url-helper (context :url-helper)
                         lang (or (context :lang) "fi")]
                     (localisation/get-translation url-helper key lang))))

(filters/add-filter! :date-format-with-dots
                     (fn [date-string]
                       (common/format-date-string-to-finnish-format date-string)))

(filters/add-filter! :date-format-with-dots-from-long
                     (fn [date-long]
                       (common/finnish-date-from-long date-long)))

(filters/add-filter! :replace-dot-with-comma
                     #(str/replace % "." ","))

(defn missing-value-fn [tag context-map]
  (log/warn "Missing template value:" (:tag-name tag) " " (:tag-value tag))
  "")

(selmer.util/set-missing-value-formatter! missing-value-fn)

(defn get-level
  [url-helper level-code lang]
  (localisation/get-translation url-helper (level-translation-key level-code) lang))

(defn get-language
  [url-helper language-code lang]
  (localisation/get-translation url-helper (str "common.language." language-code) lang))

(defn get-subtests
  [url-helper subtests lang]
  (let [translated (fn [test] (localisation/get-translation url-helper (subtest-translation-key test) lang))]
    (map translated subtests)))

(defn subject
  [url-helper template lang params]
  (let [subject (localisation/get-translation url-helper (str "email." (str/lower-case template) ".subject") lang)
        level (get-level url-helper (:level_code params) lang)
        language (get-language url-helper (:language_code params) lang)]
    (parser/render "{{subject}}: {{language}} {{level|lower}} - {{name}}, {{exam_date|date-format-with-dots}}" (assoc params :subject subject :level level :language language))))

(defn evaluation-subject
  [url-helper lang params]
  (let [subject (localisation/get-translation url-helper (str "email.evaluation_payment.subject") lang)
        level (get-level url-helper (:level_code params) lang)
        language (get-language url-helper (:language_code params) lang)]
    (parser/render "{{subject}}: {{language}} {{level|lower}}, {{exam_date|date-format-with-dots}}" (assoc params :subject subject :level level :language language))))

(defn render
  [url-helper template lang params]
  (parser/render-file (str "yki/templates/" (template-name template)) (assoc params :url-helper url-helper :lang lang)))

