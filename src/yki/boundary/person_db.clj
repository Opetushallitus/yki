(ns yki.boundary.person-db
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.common :as common])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defn- with-payment-expiry-date [registration]
  (update
    registration
    :expires_at
    (fn [v]
      (some->
        v
        (common/previous-day)
        (common/format-date-for-db)))))

(defn get-person
  [tx oid]
  (let [person        (first (q/select-person tx {:oid oid}))
        registrations (q/select-person-registrations tx {:oid oid})]
    (assoc person :registrations (map with-payment-expiry-date registrations))))

(defprotocol Person
  (upsert-person! [db person])
  (get-registration-relocate-details [db oid registration-id])
  (relocate-registration! [db oid registration-id target-exam-session-id]))

(defn valid-transfer-targets [original-exam-date targets]
  "Valid transfer targets are either within a year of the original date, or if no such exam sessions exist, the first available exam session.
   Furthermore, the transfer targets must not be already full."
  ; TODO Further down the line, should we also ensure that there is no queue to the session?
  (let [has-space?             (fn [{:keys [participants max_participants]}]
                                 (< participants max_participants))
        candidates             (filter has-space? targets)
        within-year?           #(let [exam-date  (f/parse (:session_date %1))
                                      limit-date (t/plus (f/parse original-exam-date) (t/years 1))]
                                  (not (t/after? exam-date limit-date)))
        candidates-within-year (filter within-year? candidates)]
    (if (seq candidates-within-year)
      candidates-within-year
      (->> candidates
           (sort-by :session_date)
           (take 1)))))

(extend-protocol Person
  Boundary
  (upsert-person!
    [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/upsert-person! tx person)))
  (get-registration-relocate-details [{:keys [spec]} oid registration-id]
    (jdbc/with-db-transaction [tx spec {:read-only? true}]
      (let [registration-details (-> (q/select-registration-relocate-details tx {:oid oid :id registration-id})
                                     (first))
            target-candidates    (if (:is_transferable registration-details)
                                   (q/select-transfer-target-details-by-exam-session-id tx {:exam_session_id (:exam_session_id registration-details)})
                                   [])
            targets              (valid-transfer-targets (:session_date registration-details) target-candidates)]
        (assoc registration-details :targets targets))))
  (relocate-registration! [{:keys [spec]} oid registration-id target-exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (let [registration-details (-> (q/select-registration-relocate-details tx {:oid oid :id registration-id})
                                     (first))
            target-candidates    (if (:is_transferable registration-details)
                                   (q/select-transfer-target-details-by-exam-session-id tx {:exam_session_id (:exam_session_id registration-details)})
                                   [])
            valid-target-ids     (->> (valid-transfer-targets (:session_date registration-details) target-candidates)
                                      (map :id)
                                      (into #{}))]
        (when (valid-target-ids target-exam-session-id)
          (q/relocate-registration-for-user<!
            spec
            {:registration_id registration-id
             :person_oid      oid
             :target_id       target-exam-session-id}))))))
