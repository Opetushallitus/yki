(ns yki.boundary.cas-ticket-db
  (:require
    [clojure.java.jdbc :as jdbc]
    [duct.database.sql]
    [jeesql.core :refer [require-sql]]
    [yki.boundary.db-extensions])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol CasTickets
  (create-ticket! [db ticket])
  (delete-ticket! [db ticket])
  (get-ticket [db ticket]))

(extend-protocol CasTickets
  Boundary
  (create-ticket! [{:keys [spec]} ticket]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-ticket! tx {:ticket ticket})))
  (delete-ticket! [{:keys [spec]} ticket]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-ticket! tx {:ticket ticket})))
  (get-ticket [{:keys [spec]} ticket]
    (first (q/select-ticket spec {:ticket ticket}))))
