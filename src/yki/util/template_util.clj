(ns yki.util.template-util
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [selmer.filters :as filters]
    [selmer.parser :as parser]
    [selmer.util :refer [set-missing-value-formatter!]]
    [yki.boundary.localisation :as localisation]
    [yki.util.common :as common]))

(defn- template-name [name]
  (str (str/lower-case name) ".html"))

(def level-translation-key
  {"PERUS" "common.level.basic"
   "KESKI" "common.level.middle"
   "YLIN"  "common.level.high"})

(def subtest-translation-key
  {"WRITING"   "registration.description.write"
   "LISTENING" "registration.description.listen"
   "READING"   "registration.description.read"
   "SPEAKING"  "registration.description.speak"})

(parser/cache-off!)

(parser/add-tag! :i18n
                 (fn [[key] context]
                   (let [lang (or (context :lang) "fi")]
                     (localisation/get-translation lang key))))

(filters/add-filter! :date-format-with-dots
                     (fn [date-string]
                       (common/format-date-string-to-finnish-format date-string)))

(filters/add-filter! :date-format-with-dots-from-long
                     (fn [date-long]
                       (common/finnish-date-from-long date-long)))

(filters/add-filter! :replace-dot-with-comma
                     #(str/replace % "." ","))

(defn missing-value-fn [tag _context-map]
  (log/warn "Missing template value:" (:tag-name tag) " " (:tag-value tag))
  "")

(set-missing-value-formatter! missing-value-fn)

(defn get-level
  [level-code lang]
  (localisation/get-translation lang (level-translation-key level-code)))

(defn get-language
  [language-code lang]
  (localisation/get-translation lang (str "common.language." language-code)))

(defn get-subtest [subtest lang]
  (localisation/get-translation lang (subtest-translation-key subtest)))

(defn get-subtests
  [subtests lang]
  (let [translated (fn [test] (localisation/get-translation lang (subtest-translation-key test)))]
    (map translated subtests)))

(defn subject
  [template lang params]
  (let [subject  (localisation/get-translation lang (str "email." (str/lower-case template) ".subject"))
        level    (get-level (:level_code params) lang)
        language (get-language (:language_code params) lang)]
    (parser/render "{{subject}}: {{language}} {{level|lower}} - {{name}}, {{exam_date|date-format-with-dots}}" (assoc params :subject subject :level level :language language))))

(defn evaluation-subject
  [params]
  (parser/render "{{subject}} {{language}} {{level|lower}}, {{exam_date|date-format-with-dots}}" params))

(defn render
  [template lang params]
  (parser/render-file (str "yki/templates/" (template-name template)) (assoc params :lang lang)))
