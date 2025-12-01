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
        (common/format-date-for-db)))))

(defn- get-registrations-with-queue-details
  [tx oid]
  (let [registrations         (q/select-person-registrations tx {:oid oid})
        queued-ids            (->> registrations
                                   (filter #(= "QUEUE" (:kind %)))
                                   (map :id))
        id->position-in-queue (->> (q/select-registration-queue-positions tx {:ids queued-ids})
                                   (map (fn [{:keys [id position]}]
                                          [id position]))
                                   (into {}))
        registration-details  (->> registrations
                                   (map with-payment-expiry-date)
                                   (map (fn [{:keys [id] :as v}]
                                          (if-let [pos (id->position-in-queue id)]
                                            (assoc v :position_in_queue pos)
                                            v))))]
    registration-details))

(defprotocol Person
  (get-person [db oid])
  (get-full-person-details [db oid])
  (ensure-person-exists! [db oid])
  (upsert-person! [db person])
  (update-contact-details! [db person])
  (get-persons-without-gender-or-nationality [db])
  (update-person-gender-and-nationality! [db person])
  (get-registration-relocate-details [db oid registration-id])
  (relocate-registration! [db oid registration-id target-exam-session-id])
  (get-registration-to-confirm-details [db oid registration-id])
  (cancel-person-registration! [db oid registration-id])
  (get-persons-to-sync [db retry-duration])
  (mark-person-sync-attempt! [db id success? retry-if-error?]))

(defn valid-transfer-targets
  "Valid transfer targets are either within a year of the original date, or if no such exam sessions exist, the first available exam session.
   The candidates must also have space for relocating and must not have existing queue; the caller of this function should ensure this.
   Finally, the person must not already be enrolled for an exam on the date of a candidate session; also something that the caller should ensure."
  [original-exam-date candidates]
  (let [within-year?           #(let [exam-date  (f/parse (:session_date %1))
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
  (get-person [{:keys [spec]} oid]
    (jdbc/with-db-transaction [tx spec]
      (some->
        (q/select-person tx {:oid oid})
        (first)
        (assoc :registrations (get-registrations-with-queue-details tx oid)))))
  (get-full-person-details [{:keys [spec]} oid]
    (first (q/select-full-person-details spec {:oid oid})))
  (get-persons-without-gender-or-nationality [{:keys [spec]}]
    (let [persons (q/select-persons-without-gender-or-nationality spec)]
      (->> persons
           (map (fn [{:keys [person_oid form]}]
                  {:oid           person_oid
                   :gender        (:gender form)
                   :ssn           (:ssn form)
                   :nationalities (:nationalities form)})))))
  (update-person-gender-and-nationality! [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/update-person-gender-and-nationality! tx person)))
  (ensure-person-exists! [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/ensure-person-exists! tx person)))
  (upsert-person!
    [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/upsert-person! tx person)))
  (update-contact-details!
    [{:keys [spec]} person]
    (jdbc/with-db-transaction [tx spec]
      (q/update-person-contact-details! tx person)
      (q/schedule-person-to-be-synced! tx person)))
  (get-registration-relocate-details [{:keys [spec]} oid registration-id]
    (jdbc/with-db-transaction [tx spec {:read-only? true}]
      (let [registration-details (-> (q/select-registration-relocate-details tx {:oid oid :id registration-id})
                                     (first))
            target-candidates    (if (:is_transferable registration-details)
                                   (q/select-registration-transfer-target-details tx {:exam_session_id (:exam_session_id registration-details)
                                                                                      :registration_id registration-id})
                                   [])
            targets              (valid-transfer-targets (:session_date registration-details) target-candidates)]
        (assoc registration-details :targets targets))))
  (relocate-registration! [{:keys [spec]} oid registration-id target-exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (let [registration-details (-> (q/select-registration-relocate-details tx {:oid oid :id registration-id})
                                     (first))
            target-candidates    (if (:is_transferable registration-details)
                                   (q/select-registration-transfer-target-details tx {:exam_session_id (:exam_session_id registration-details)
                                                                                      :registration_id registration-id})
                                   [])
            valid-target-ids     (->> (valid-transfer-targets (:session_date registration-details) target-candidates)
                                      (map :id)
                                      (into #{}))]
        (when (valid-target-ids target-exam-session-id)
          (q/relocate-registration-for-user<!
            spec
            {:registration_id registration-id
             :person_oid      oid
             :target_id       target-exam-session-id})))))
  (get-registration-to-confirm-details [{:keys [spec]} oid registration-id]
    (first (q/select-registration-to-confirm-details spec {:oid oid :id registration-id})))
  (cancel-person-registration! [{:keys [spec]} oid registration-id]
    (jdbc/with-db-transaction [tx spec]
      (q/cancel-registration-for-person<! tx {:oid oid :id registration-id})))
  (get-persons-to-sync [{:keys [spec]} retry-duration]
    (q/select-persons-to-sync spec {:duration retry-duration}))
  (mark-person-sync-attempt! [{:keys [spec]} id success? retry-if-error?]
    (jdbc/with-db-transaction [tx spec]
      (if success?
        (q/mark-successful-person-sync-attempt! tx {:id id})
        (q/mark-failed-person-sync-attempt! tx {:id id :should_retry retry-if-error?})))))
