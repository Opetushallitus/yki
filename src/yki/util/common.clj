(ns yki.util.common
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.string :as str]))

(def date-format "YYYY-MM-dd")
(def finnish-date-formatter (f/formatter "d.M.YYYY"))
(def time-format "HH:mm")

(defn next-start-of-day [local-date]
  (t/plus local-date (t/days 1)))

(defn previous-day [local-date]
  (t/minus local-date (t/days 1)))

(defn date-from-now [days]
  (t/plus (t/today) (t/days days)))

(defn format-date-to-finnish-format [local-date]
  (f/unparse-local-date finnish-date-formatter local-date))

(defn format-date-string-to-finnish-format [date-string]
  (f/unparse finnish-date-formatter (f/parse date-string)))

(defn finnish-date-from-long [date-long]
  (f/unparse finnish-date-formatter (c/from-long date-long)))

(defn string->date [datestr]
  (some-> datestr (f/parse)))

;; Allow digits, unicode letters, space and chars '``*+@&.,-_
(defn sanitized-string [replacement input]
  (when input
    (str/replace (str/trim input) #"[^0-9\-\'\´\`\p{L}\p{M}*+.,_&@ ]" replacement)))

(def export-datetime-formatter (f/formatter "yyyy-MM-dd HH:mm:ss"))

(defn format-datetime-for-export [datetime]
  (f/unparse export-datetime-formatter datetime))

(defn format-date-for-db [date]
  (f/unparse (f/formatter date-format) date))
