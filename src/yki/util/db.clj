(ns yki.util.db
  (:require [clojure.tools.logging :as log])
  (:import [java.sql Connection]))

(defn rollback-on-exception [{:keys [^Connection connection]} f]
  {:pre [(instance? Connection connection)
         (fn? f)]}
  (try
    (f)
    (catch Exception e
      (.rollback connection)
      (log/error e "Execution failed. Rolling back transaction.")
      (throw e))))
