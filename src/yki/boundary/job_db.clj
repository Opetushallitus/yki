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
  (try-to-acquire-lock! [db config]))

(extend-protocol Job
  Boundary
  (try-to-acquire-lock!
    [{:keys [spec]} {:keys [worker-id task interval]}]
    (jdbc/with-db-transaction [tx spec]
      (pos? (q/try-to-acquire-lock! tx {:task task, :worker_id worker-id, :interval interval})))))
