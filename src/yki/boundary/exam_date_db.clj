(ns yki.boundary.exam-date-db
  (:require
   [jeesql.core :refer [require-sql]]
   [clojure.java.jdbc :as jdbc]
   [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol ExamDate
  (get-exam-dates [db]))

(extend-protocol ExamDate
  duct.database.sql.Boundary
  (get-exam-dates [{:keys [spec]}]
    (q/select-exam-dates spec)))
