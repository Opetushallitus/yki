(ns yki.boundary.person-db
  (:require [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.registration.change-event :refer [registration->change-event]]
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
  (get-registration-to-confirm-details [db oid registration-id])
  (cancel-person-registration! [db oid registration-id])
  (get-persons-to-sync [db retry-duration])
  (mark-person-sync-attempt! [db id success? retry-if-error?]))

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
      (let [person-with-country-code
            (if (contains? person :country_code)
              person
              (if-let [existing (first (q/select-person tx {:oid (:oid person)}))]
                (assoc person :country_code (:country_code existing))
                person))]
        (q/update-person-contact-details! tx person-with-country-code)
        (q/schedule-person-to-be-synced! tx person))))
  (get-registration-to-confirm-details [{:keys [spec]} oid registration-id]
    (first (q/select-registration-to-confirm-details spec {:oid oid :id registration-id})))
  (cancel-person-registration! [{:keys [spec]} oid registration-id]
    (jdbc/with-db-transaction [tx spec]
      (when-let [canceled (q/cancel-registration-for-person<! tx {:oid oid :id registration-id})]
        (q/insert-registration-change-event!
          tx
          (merge (registration->change-event canceled)
                 {:event       "CANCEL"
                  :author_type "USER"
                  :created_by  oid}))
        canceled)))
  (get-persons-to-sync [{:keys [spec]} retry-duration]
    (q/select-persons-to-sync spec {:duration retry-duration}))
  (mark-person-sync-attempt! [{:keys [spec]} id success? retry-if-error?]
    (jdbc/with-db-transaction [tx spec]
      (if success?
        (q/mark-successful-person-sync-attempt! tx {:id id})
        (q/mark-failed-person-sync-attempt! tx {:id id :should_retry retry-if-error?})))))
