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

(defn- convert-dates [quarantine]
  (assoc quarantine :start_date (f/parse (:start_date quarantine))
                    :end_date (f/parse (:end_date quarantine))))

(defn- without-nils [kvs]
  (->> kvs
       (filter (fn [[_ v]] (some? v)))
       (into {})))

(defn- with-placeholders-for-optional-values [quarantine]
  (merge {:ssn nil :phone_number nil :email nil} quarantine))

(defprotocol Quarantine
  (create-quarantine! [db quarantine])
  (update-quarantine! [db id quarantine])
  (delete-quarantine! [db id])
  (get-quarantines [db])
  (get-quarantine [db id])
  (get-quarantine-matches [db])
  (set-registration-quarantine! [db quarantine-id registration-id quarantined reviewer-oid])
  (get-reviews [db]))

(extend-protocol Quarantine
  Boundary
  (create-quarantine! [{:keys [spec]} quarantine]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-quarantine<!
        tx
        (-> quarantine
            (convert-dates)
            (with-placeholders-for-optional-values)))))
  (update-quarantine! [{:keys [spec]} id quarantine]
    (jdbc/with-db-transaction [tx spec]
      (q/update-quarantine<! tx (-> quarantine
                                    (assoc :id id)
                                    (convert-dates)
                                    (with-placeholders-for-optional-values)))))
  (delete-quarantine! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/delete-quarantine! tx {:id id}))))
  (get-quarantines [{:keys [spec]}]
    (->> (q/select-quarantines spec)
         (map without-nils)))
  (get-quarantine [{:keys [spec]} id]
    (first (q/select-quarantine spec {:id id})))
  (get-quarantine-matches [{:keys [spec]}]
    (->> (q/select-quarantine-matches spec)
         (map without-nils)))
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
                                             :reviewer_oid    reviewer-oid})))))
  (get-reviews [{:keys [spec]}]
    (->> (q/select-quarantine-reviews spec)
         (map without-nils))))
