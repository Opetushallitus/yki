; THE ONLY REASON FOR THIS FILE IS TO CIRCUMVENT LOGBACK LOGSTREAM AUTODETECTION
; USED ONLY FROM AUDIT LOG BECAUSE LOGBACK CAPTURES LOGGING CALLS TO ACTUAL AUDIT LOG
(ns yki.util.log-util
  (:require [clojure.tools.logging :as log]))

(defn error [e error-text change]
  (log/error e error-text change))
