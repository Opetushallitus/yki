(ns yki.boundary.evaluation-period-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.jdbc]
            [yki.boundary.db-extensions]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol ExamSessions
  (get-upcoming-evaluation-periods [db]))

(extend-protocol ExamSessions
  duct.database.sql.Boundary
  (get-upcoming-evaluation-periods [{:keys [spec]}]
    (q/select-upcoming-evalution-periods spec)))
