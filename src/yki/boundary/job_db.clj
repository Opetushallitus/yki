(ns yki.boundary.job-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql])
  (:import (duct.database.sql Boundary)))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Job
  "Returns true if lock is successfully acquired.
  Lock is used for ensuring that single instance of a job is running."
  (try-to-acquire-lock! [db config])
  (get-runtime-flag [db flag-name]
    "nil when the flag, or its table, does not exist yet. The read has its own short transaction, so
    the share lock of the query ends before this returns. A job that must not run at the same time as
    its Java port reads the flag again in the transaction that writes, as the Java port does not take
    this backend's task_lock row."))

(extend-protocol Job
  Boundary
  (try-to-acquire-lock!
    [{:keys [spec]} {:keys [worker-id task interval]}]
    (jdbc/with-db-transaction [tx spec]
      (pos? (q/try-to-acquire-lock! tx {:task task, :worker_id worker-id, :interval interval}))))
  (get-runtime-flag
    [{:keys [spec]} flag-name]
    (jdbc/with-db-transaction [tx spec]
      (when (:table_exists (first (q/select-runtime-flag-table-exists tx)))
        (:value (first (q/select-runtime-flag-for-share tx {:name flag-name})))))))
