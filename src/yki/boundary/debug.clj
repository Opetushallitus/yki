(ns yki.boundary.debug
  (:require
    [clojure.java.jdbc :as jdbc]
    [jeesql.core :refer [require-sql]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/debug.sql" :as q])
(require-sql ["yki/queries.sql" :as queries])

(defn- participant-onr-sync-owner
  "Read under a share lock, so the owner cannot change before this transaction commits. nil when the
  flag, or its table, does not exist yet."
  [tx]
  (when (:table_exists (first (queries/select-runtime-flag-table-exists tx)))
    (:value (first (queries/select-runtime-flag-for-share tx {:name "participant_onr_sync.owner"})))))

(defprotocol ParticipantOnrDebug
  (get-participant-onr-data [db])
  (get-participants-for-onr-check [db])
  (upsert-participant-onr-data-batch! [db participants owns-sync?]
    "true when the batch was written. Switching the owner waits for a batch being written, and every
    batch written after the switch sees the new owner, so the two backends never write side by side."))

(extend-protocol ParticipantOnrDebug
  Boundary
  (get-participant-onr-data [db]
    (q/select-participant-onr-data (:spec db)))
  (get-participants-for-onr-check [db]
    (q/select-participants-for-onr-check (:spec db)))
  (upsert-participant-onr-data-batch! [{:keys [spec]} participants owns-sync?]
    (jdbc/with-db-transaction [tx spec]
      (when (owns-sync? (participant-onr-sync-owner tx))
        (doseq [participant participants]
          (q/upsert-participant-onr-data! tx participant))
        true))))
