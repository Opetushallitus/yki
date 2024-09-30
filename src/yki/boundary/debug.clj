(ns yki.boundary.debug
  (:require
    [jeesql.core :refer [require-sql]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/debug.sql" :as q])

(defprotocol ParticipantOnrDebug
  (get-participant-onr-data [db])
  (get-participants-for-onr-check [db])
  (upsert-participant-onr-data! [db participant]))

(extend-protocol ParticipantOnrDebug
  Boundary
  (get-participant-onr-data [db]
    (q/select-participant-onr-data (:spec db)))
  (get-participants-for-onr-check [db]
    (q/select-participants-for-onr-check (:spec db)))
  (upsert-participant-onr-data! [db participant]
    (q/upsert-participant-onr-data! (:spec db) participant)))
