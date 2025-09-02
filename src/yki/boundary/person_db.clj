(ns yki.boundary.person-db
  (:require [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Person
  (migrate-persons! [db]))

(extend-protocol Person
  Boundary
  (migrate-persons!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (q/migrate-persons! tx))))
