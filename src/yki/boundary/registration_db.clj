(ns yki.boundary.registration-db
  (:require [clj-time.core :as t]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.registration.change-event :refer [registration->change-event]]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (is-person-already-registered-on-exam-date? [db person-oid registration-id])
  (update-registration-details! [db session registration after-fn])
  (update-participant-external-id! [db participant])
  (update-registration-participant-id! [db registration-id participant-id])
  (get-registration-data-for-new-payment [db registration-id external-user-id])
  (get-new-payment-details [db transaction-id])
  (complete-new-payment-and-exam-registration! [db registration-id payment-id after-fn])
  ; Other methods
  (get-participant-by-id [db id])
  (get-participant-by-external-id [db external-id])
  (participant-registered-to-other-exam-on-exam-date? [db participant-id exam-session-id])
  (participant-registered-to-exam-on-exam-date? [db participant-id exam-session-id])
  (person-registered-to-exam-on-exam-date? [db registration-id exam-session-id])
  (get-started-registration-id+kind-by-participant-id [db participant-id exam-session-id partial-exam-type])
  (get-started-registration-kind+type-by-id [db exam-session-id registration-id])
  (create-registration! [db session registration])
  (update-started-registration-oid! [db registration-id person-oid])
  (get-registration-data [db registration-id participant-id lang])
  (get-registration-and-exam-session-state [db registration-id])
  (get-registration-data-by-participant [db registration-id participant-id lang])
  (get-completed-registration-data [db exam-session-id registration-id lang])
  (get-registration-data-for-clerk-mail [db exam-session-id registration-id])
  (get-completed-payment-data-for-registration [db registration-id])
  (get-open-registrations-by-participant [db participant-id])
  (exam-session-space-left? [db exam-session-id registration-id partial-exam-type])
  (exam-session-registration-open? [db exam-session-id])
  (update-participant-email! [db email participant-id])
  (get-participant-data-by-registration-id [db registration-id])
  (get-or-create-participant! [db participant])
  (update-started-registrations-to-expired! [db])
  (update-submitted-registrations-to-expired! [db])
  (cancel-started-registration-for-participant! [db session participant-id registration-id])
  (get-started-registration-expires-in [db registration-id])
  ; Queueing
  (get-participant-and-queue-count-for-ongoing-admissions [db])
  (lift-registration-from-queue! [db exam-session-id send-email!])
  (expire-queued-registrations-after-exam-date! [db])
  (get-free-registration [db registration-id]))

(defn get-registration-data-with-tx
  [tx registration-id participant-id lang]
  (first (q/select-registration-data tx {:id registration-id :participant_id participant-id :lang lang})))

(defn- expire-registrations! [tx ids]
  (when (seq ids)
    (let [expired (q/expire-registrations-by-ids! tx {:ids ids})]
      (when (not= expired (count ids))
        (log/error "Mismatch between expected and actual expired ids count! Statistics from registration_change_event entries are likely distorted as a result!"
                   {:actual   expired
                    :expected (count ids)}))
      (q/insert-registration-change-events-for-expired-ids! tx {:ids ids})
      ids)))

(extend-protocol Registration
  Boundary
  (is-person-already-registered-on-exam-date?
    [{:keys [spec]} person-oid registration-id]
    (let [exists (first (q/select-person-has-other-registrations-for-same-day spec {:oid person-oid
                                                                                    :id  registration-id}))]
      (:exists exists)))
  (get-participant-by-id
    [{:keys [spec]} id]
    (first (q/select-participant-by-id spec {:id id})))
  (get-participant-by-external-id
    [{:keys [spec]} external-id]
    (first (q/select-participant-by-external-id spec {:external_user_id external-id})))
  (participant-registered-to-other-exam-on-exam-date?
    [{:keys [spec]} participant-id exam-session-id]
    (first (q/select-participant-registered-to-other-exam-on-exam-date
            spec {:participant_id  participant-id
                  :exam_session_id exam-session-id})))
  (participant-registered-to-exam-on-exam-date?
    [{:keys [spec]} participant-id exam-session-id]
    (first (q/select-participant-registered-to-exam-on-exam-date
            spec {:participant_id  participant-id
                  :exam_session_id exam-session-id})))
  (person-registered-to-exam-on-exam-date?
    [{:keys [spec]} registration-id exam-session-id]
    (first (q/select-person-registered-to-exam-on-exam-date
            spec {:registration_id registration-id
                  :exam_session_id exam-session-id})))
  (get-started-registration-id+kind-by-participant-id
    [{:keys [spec]} participant-id exam-session-id partial-exam-type]
    (first (q/select-started-registration-id-and-kind-by-participant spec {:participant_id    participant-id
                                                                           :exam_session_id   exam-session-id
                                                                           :partial_exam_type partial-exam-type})))
  (get-started-registration-kind+type-by-id
    [{:keys [spec]} exam-session-id registration-id]
    (first (q/select-started-registration-kind-and-type-by-id spec {:exam_session_id exam-session-id
                                                                    :registration_id registration-id})))
  (exam-session-space-left?
    [{:keys [spec]} exam-session-id registration-id partial-exam-type]
    (let [exists (first (q/select-exam-session-space-left spec {:exam_session_id exam-session-id
                                                                :registration_id registration-id
                                                                :partial_exam_type partial-exam-type}))]
      (:exists exists)))
  (exam-session-registration-open?
    [{:keys [spec]} id]
    (let [exists (first (q/select-exam-session-registration-open spec {:exam_session_id id}))]
      (:exists exists)))
  (update-participant-external-id!
    [{:keys [spec]} participant]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-external-id! tx participant)))
  (update-registration-participant-id!
    [{:keys [spec]} registration-id participant-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-registration-participant-id! tx {:registration_id registration-id
                                                 :participant_id  participant-id})))
  (update-participant-email!
    [{:keys [spec]} email participant-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-email! tx {:email email :id participant-id})))
  (update-registration-details!
    [{:keys [spec]} session registration after-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(when-let [updated (q/update-registration-to-submitted<! tx registration)]
          (q/insert-registration-change-event!
           tx
           (merge (registration->change-event updated)
                  {:event       "SUBMIT"
                   :author_type "USER"
                   :created_by  (get-in session [:identity :oid])}))
          (after-fn)
          updated))))
  (create-registration!
    [{:keys [spec]} session registration]
    (jdbc/with-db-transaction [tx spec]
      (when-let [created (q/insert-registration<! tx registration)]
        (q/insert-registration-change-event!
         tx
         (merge
          (registration->change-event created)
          {:event       "CREATE"
           :author_type "USER"
           :created_by  (get-in session [:identity :oid])}))
        (select-keys created [:id :partial_exam_type]))))
  (update-started-registration-oid!
    [{:keys [spec]} registration-id person-oid]
    (jdbc/with-db-transaction [tx spec]
      (q/update-started-registration-oid! tx {:id  registration-id
                                              :oid person-oid})))
  (update-started-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-started-registrations-to-expire tx)
                     (map :id))]
        (expire-registrations! tx ids))))
  (update-submitted-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-submitted-registrations-to-expire tx)
                     (map :id))]
        (expire-registrations! tx ids))))
  (get-participant-data-by-registration-id
    [{:keys [spec]} registration-id]
    (first (q/select-participant-data-by-registration-id spec {:id registration-id})))
  (get-registration-data
    [{:keys [spec]} registration-id participant-id lang]
    (first (q/select-registration-data spec {:id registration-id :participant_id participant-id :lang lang})))
  (get-registration-and-exam-session-state [{:keys [spec]} registration-id]
    (first (q/select-registration-and-exam-session-state spec {:id registration-id})))
  (get-registration-data-by-participant
    [{:keys [spec]} registration-id participant-id lang]
    (first (q/select-registration-data-by-participant spec {:id registration-id :participant_id participant-id :lang lang})))
  (get-completed-registration-data
    [{:keys [spec]} exam-session-id registration-id lang]
    (first (q/select-completed-registration-details spec {:id              registration-id
                                                          :exam_session_id exam-session-id
                                                          :lang            lang})))
  (get-registration-data-for-clerk-mail
    [{:keys [spec]} exam-session-id registration-id]
    (first (q/select-registration-details-for-clerk-mail spec {:id              registration-id
                                                               :exam_session_id exam-session-id})))
  (get-completed-payment-data-for-registration
    [{:keys [spec]} registration-id]
    (first (q/select-completed-payment-details-for-registration spec {:registration_id registration-id})))
  (get-registration-data-for-new-payment
    [{:keys [spec]} registration-id external-user-id]
    (first (q/select-registration-details-for-new-payment spec {:id registration-id :external_user_id external-user-id})))
  (get-open-registrations-by-participant
    [{:keys [spec]} external-user-id]
    (q/select-open-registrations-by-participant spec {:external_user_id external-user-id}))
  (get-or-create-participant!
    [{:keys [spec]} participant]
    (jdbc/with-db-transaction [tx spec]
      (if-let [existing (first (q/select-participant-by-external-id tx participant))]
        existing
        (q/insert-participant<! tx participant))))
  (get-new-payment-details [{:keys [spec]} transaction-id]
    (first (q/select-new-exam-payment-details spec {:transaction_id transaction-id})))
  (complete-new-payment-and-exam-registration! [{:keys [spec]} registration-id payment-id after-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       (fn update-payment-and-registration-states! []
         (let [updated-payment-details (q/update-new-exam-payment-to-paid<! tx {:id payment-id})
               updated-registration    (q/complete-registration<! tx {:id registration-id})
               new-state               (:state updated-registration)]
           (when (= "COMPLETED" new-state)
             (after-fn updated-payment-details))
           (when (#{"COMPLETED" "PAID_AND_CANCELLED"} new-state)
             (q/insert-registration-change-event!
              tx
              (merge (registration->change-event updated-registration)
                     {:event       "COMPLETE_PAYMENT"
                      :author_type "INTEGRATION"
                      :created_by  nil})))
           updated-registration)))))
  (cancel-started-registration-for-participant! [{:keys [spec]} session participant-id registration-id]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       (fn cancel-registration! []
         (when-let [canceled (q/cancel-started-registration-for-participant<!
                              tx
                              {:id             registration-id
                               :participant_id participant-id})]
           (q/insert-registration-change-event!
            tx
            (merge (registration->change-event canceled)
                   {:event       "CANCEL"
                    :author_type "USER"
                    :created_by  (get-in session [:identity :oid])}))
           canceled)))))
  (get-started-registration-expires-in [{:keys [spec]} registration-id]
    (let [expires-at (q/select-started-registration-expires-at spec {:id registration-id})
          now        (t/now)]
      (if (and expires-at (t/before? now expires-at))
        (t/in-seconds (t/interval (t/now) expires-at))
        0)))
  (get-participant-and-queue-count-for-ongoing-admissions [{:keys [spec]}]
    (q/select-participant-and-queue-count-by-exam-session spec))
  (lift-registration-from-queue! [{:keys [spec]} exam-session-id send-email!]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       (fn lift-registration-and-notify! []
         (let [registration (q/lift-registration-from-queue<! tx {:exam_session_id exam-session-id})]
           (q/insert-registration-change-event!
            tx
            (merge (registration->change-event registration)
                   {:event       "LIFT_FROM_QUEUE"
                    :author_type "AUTOMATION"
                    :created_by  nil}))
           (send-email! tx registration))))))
  (expire-queued-registrations-after-exam-date! [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-queued-registrations-to-expire tx)
                     (map :id))]
        (expire-registrations! tx ids))))
  (get-free-registration [{:keys [spec]} registration-id]
    (first (q/select-free-registration spec {:id registration-id}))))
