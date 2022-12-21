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

(defn- int->boolean [value]
  (= value 1))

(defn- convert-date [quarantine]
  (update-in quarantine [:end_date] f/parse))

(defprotocol Quarantine
  (create-quarantine! [db quarantine])
  (delete-quarantine! [db id])
  (get-quarantine [db])
  (get-quarantine-matches [db])
  (set-registration-quarantine! [db id reg-id quarantined]))

(extend-protocol Quarantine
  Boundary
  (create-quarantine! [{:keys [spec]} quarantine]
     (jdbc/with-db-transaction [tx spec]
       (int->boolean (q/insert-quarantine! tx (convert-date quarantine)))))
  (delete-quarantine! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/delete-quarantine! tx {:id id}))))
  (get-quarantine [{:keys [spec]}]
    (q/select-quarantine spec))
  (get-quarantine-matches [{:keys [spec]}]
    (q/select-quarantine-matches spec))
  (set-registration-quarantine! [{:keys [spec]} id reg-id quarantined]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/update-registration-quarantine! tx {:id (when quarantined id)
                                                           :reg_id reg-id
                                                           :quarantined quarantined})))))
