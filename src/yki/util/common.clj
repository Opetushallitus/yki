(ns yki.util.common
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.string :as str]))

(def date-format "YYYY-MM-dd")

(def finnish-date-formatter (f/formatter "d.M.YYYY"))

(def time-format "HH:mm")

(def timezone (t/time-zone-for-id "Europe/Helsinki"))

(defn set-timezone [date]
  (t/from-time-zone date timezone))

(defn next-start-of-day [date]
  (set-timezone (t/with-time-at-start-of-day (t/plus date (t/days 1)))))

(defn date-from-now [days]
  (set-timezone (t/with-time-at-start-of-day (t/plus (t/now) (t/days (inc days))))))

(defn format-date-to-finnish-format [date]
  (f/unparse finnish-date-formatter date))

(defn format-date-string-to-finnish-format [date-string]
  (f/unparse finnish-date-formatter (f/parse date-string)))

(defn finnish-date-from-long [date-long]
  (format-date-to-finnish-format (c/from-long date-long)))

;; Allow digits, unicode letters, space and chars '``*+@&.,-_
(defn sanitized-string [replacement input]
  (str/replace (str/trim input) #"[^0-9\-\'\´\`\p{L}\p{M}*+.,_&@ ]" replacement))

;; iterates over map and applies supplied function to map's values
(defn transform-map-values [fun map]
  (into {} (for [[key val] map] [key (fun val)])))
