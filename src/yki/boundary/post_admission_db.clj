(ns yki.boundary.post-admission-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol PostAdmission
  (do-the-needful [db]))

(extend-protocol PostAdmission
  duct.database.sql.Boundary
  (do-the-needful [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (q/do-the-needful tx))))
