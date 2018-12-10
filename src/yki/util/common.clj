(ns yki.util.common
  (:require [clj-time.core :as t]))

(def date-format "YYYY-MM-dd")

(def time-format "HH:mm")

(def timezone (t/time-zone-for-id "Europe/Helsinki"))

(defn set-timezone [date]
  (t/from-time-zone date timezone))

(defn next-start-of-day [date]
  (set-timezone (t/with-time-at-start-of-day (t/plus date (t/days 1)))))

(defn date-from-now [days]
  (set-timezone (t/with-time-at-start-of-day (t/plus (t/now) (t/days (+ days 1))))))
