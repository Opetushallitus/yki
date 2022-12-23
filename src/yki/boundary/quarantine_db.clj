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
  (update-quarantine! [db id quarantine])
  (delete-quarantine! [db id])
  (get-quarantines [db])
  (get-quarantine [db id])
  (get-quarantine-matches [db])
  (set-registration-quarantine! [db quarantine-id registration-id quarantined reviewer-oid]))

(extend-protocol Quarantine
  Boundary
  (create-quarantine! [{:keys [spec]} quarantine]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-quarantine<! tx (convert-date quarantine))))
  (update-quarantine! [{:keys [spec]} id quarantine]
    (jdbc/with-db-transaction [tx spec]
      (q/update-quarantine<! tx (-> (assoc quarantine :id id)
                                    (convert-date)))))
  (delete-quarantine! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/delete-quarantine! tx {:id id}))))
  (get-quarantines [{:keys [spec]}]
    (q/select-quarantines spec))
  (get-quarantine [{:keys [spec]} id]
    (first (q/select-quarantine spec {:id id})))
  (get-quarantine-matches [{:keys [spec]}]
    (q/select-quarantine-matches spec))
  (set-registration-quarantine! [{:keys [spec]} quarantine-id registration-id quarantined reviewer-oid]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(do
           (when quarantined
             (q/cancel-registration! tx {:id registration-id}))
           (q/upsert-quarantine-review<! tx {:quarantine_id   quarantine-id
                                            :registration_id registration-id
                                            :quarantined     quarantined
                                            :reviewer_oid    reviewer-oid}))))))
