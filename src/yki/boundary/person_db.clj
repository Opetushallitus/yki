(ns yki.boundary.person-db
  (:require [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Person
  (upsert-person! [db person])
  (get-person [db oid lang]))

(extend-protocol Person
  Boundary
  (upsert-person!
    [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/upsert-person! tx person)))
  (get-person
    [{:keys [spec]} oid lang]
    (jdbc/with-db-transaction [tx spec]
      (assoc (first (q/select-person tx {:oid oid}))
             :registrations (q/select-person-registrations tx {:oid oid :lang lang})))))
