(ns yki.boundary.person-db
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.common :as common])
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
