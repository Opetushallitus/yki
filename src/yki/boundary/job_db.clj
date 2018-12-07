(ns yki.boundary.job-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Job
  "Returns true if lock is succesfully acquired."
  "Lock is used for ensuring that single instance of a job is running."
  (try-to-acquire-lock! [db config]))

(extend-protocol Job
  duct.database.sql.Boundary
  (try-to-acquire-lock!
    [{:keys [spec]} {:keys [worker-id task interval]}]
    (jdbc/with-db-transaction [tx spec]
      (> (q/try-to-acquire-lock! tx {:worker_id worker-id :task task :interval interval}) 0))))
