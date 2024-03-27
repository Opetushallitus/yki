(ns yki.boundary.cas-ticket-db
  (:require
    [clojure.java.jdbc :as jdbc]
    [duct.database.sql]
    [jeesql.core :refer [require-sql]]
    [yki.boundary.db-extensions])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol CasTickets
  (create-ticket! [db cas-variant ticket])
  (delete-ticket! [db cas-variant ticket])
  (get-ticket [db cas-variant ticket]))

(extend-protocol CasTickets
  Boundary
  (create-ticket! [{:keys [spec]} cas-variant ticket]
    (jdbc/with-db-transaction [tx spec]
      (case cas-variant
        :oppija
        (q/insert-oppija-ticket! tx {:ticket ticket})
        :virkailija
        (q/insert-virkailija-ticket! tx {:ticket ticket}))))
  (delete-ticket! [{:keys [spec]} cas-variant ticket]
    (jdbc/with-db-transaction [tx spec]
      (case cas-variant
        :oppija
        (q/delete-oppija-ticket! tx {:ticket ticket})
        :virkailija
        (q/delete-virkailija-ticket! tx {:ticket ticket}))))
  (get-ticket [{:keys [spec]} cas-variant ticket]
    (case cas-variant
      :oppija
      (first (q/select-oppija-ticket spec {:ticket ticket}))
      :virkailija
      (first (q/select-virkailija-ticket spec {:ticket ticket})))))
