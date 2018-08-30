(ns yki.boundary.status_db
  (:require [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(defprotocol Status
  (get-status [db]))

(extend-protocol Status
  duct.database.sql.Boundary
  (get-status [{:keys [spec]}]
    (jdbc/query spec ["SELECT 1"])))
