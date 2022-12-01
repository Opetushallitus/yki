(ns yki.boundary.quarantine-db
  (:require [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Quarantine
  (get-quarantine [db])
  (get-quarantine-matches [db])
  (update-registration-quarantine! [db id reg-id quarantined]))

(extend-protocol Quarantine
  Boundary
  (get-quarantine [{:keys [spec]}]
    (q/select-quarantine spec))
  (get-quarantine-matches [{:keys [spec]}]
    (q/select-quarantine-matches spec))
  (update-registration-quarantine! [{:keys [spec]} id reg-id quarantined]
    (q/update-registration-quarantine! spec)))
