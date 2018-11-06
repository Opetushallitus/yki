(ns yki.boundary.job-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Job
  (try-to-acquire-lock! [db worker-id task interval]))

(extend-protocol Job
  duct.database.sql.Boundary
  (try-to-acquire-lock!
    [{:keys [spec]} worker-id task interval]
    (jdbc/with-db-transaction [tx spec]
      (q/try-to-acquire-lock! tx {:worker_id worker-id :task task :interval interval}))))
