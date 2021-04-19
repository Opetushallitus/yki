(ns yki.boundary.evaluation-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.jdbc]
            [yki.boundary.db-extensions]
            [duct.database.sql]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]))

(require-sql ["yki/queries.sql" :as q])

(defn rollback-on-exception [tx f]
  (try
    (f)
    (catch Exception e
      (.rollback (:connection tx))
      (log/error e "Execution failed. Rolling back transaction.")
      (throw e))))

(defprotocol ExamSessions
  (get-upcoming-evaluation-periods [db])
  (get-evaluation-period-by-id [db id])
  (get-subtests [db])
  (create-evaluation-order! [db evaluation-id evaluation-order]))

(extend-protocol ExamSessions
  duct.database.sql.Boundary
  (get-upcoming-evaluation-periods [{:keys [spec]}]
    (q/select-upcoming-evalution-periods spec))
  (get-evaluation-period-by-id [{:keys [spec]} id]
    (first (q/select-evaluation-by-id spec {:evaluation_id id})))
  (get-subtests [{:keys [spec]}]
    (q/select-subtests spec))
  (create-evaluation-order!
    [{:keys [spec]} evaluation-id evaluation-order]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [result (q/insert-evaluation-order<! tx (assoc evaluation-order :evaluation_id evaluation-id))
              evaluation-order-id (:id result)
              print-result (println "Inserted evaluation order with id: " evaluation-order-id)]
          (doseq [subtest (:subtests evaluation-order)]
            (q/insert-evaluation-order-subtest! tx {:subtest subtest
                                                    :evaluation_order_id evaluation-order-id}))
          evaluation-order-id)))))
