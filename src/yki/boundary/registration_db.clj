(ns yki.boundary.registration-db
  (:require [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (is-person-already-registered-on-exam-date? [db person-oid registration-id])
  (update-registration-details! [db registration after-fn])
  (update-participant-external-id! [db participant])
  (update-registration-participant-id! [db registration-id participant-id])
  (get-registration-data-for-new-payment [db registration-id external-user-id])
  (get-new-payment-details [db transaction-id])
  (complete-new-payment-and-exam-registration! [db registration-id payment-id after-fn])
  ; Other methods
  (get-participant-by-id [db id])
  (get-participant-by-external-id [db external-id])
  (registered-to-other-exam-session-on-exam-date? [db participant-id exam-session-id])
  (is-registered-to-other-exam-session? [db participant-id exam-session-id])
  (get-started-registration-id+kind-by-participant-id [db participant-id exam-session-id])
  (create-registration! [db registration])
  (get-registration-data [db registration-id participant-id lang])
  (get-registration-and-exam-session-state [db registration-id])
  (get-registration-data-by-participant [db registration-id participant-id lang])
  (get-completed-registration-data [db exam-session-id registration-id lang])
  (get-registration-data-for-clerk-mail [db exam-session-id registration-id])
  (get-completed-payment-data-for-registration [db registration-id])
  (get-open-registrations-by-participant [db participant-id])
  (exam-session-space-left? [db exam-session-id registration-id])
  (exam-session-registration-open? [db exam-session-id])
  (update-participant-email! [db email participant-id])
  (get-participant-data-by-registration-id [db registration-id])
  (get-registration [db registration-id external-user-id])
  (get-or-create-participant! [db participant])
  (update-started-registrations-to-expired! [db])
  (update-submitted-registrations-to-expired! [db])
  (cancel-started-registration-for-participant! [db participant-id registration-id])
  ; Queueing
  (get-participant-and-queue-count-for-ongoing-admissions [db])
  (lift-registration-from-queue! [db exam-session-id send-email!])
  (expire-queued-registrations-after-exam-date! [db]))

(defn- int->boolean [value]
  (pos? value))

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
  (registered-to-other-exam-session-on-exam-date?
    [{:keys [spec]} participant-id exam-session-id]
    (first (q/select-registered-to-other-exam-session-on-exam-date
             spec {:participant_id  participant-id
                   :exam_session_id exam-session-id})))
  (is-registered-to-other-exam-session?
    [{:keys [spec]} participant-id exam-session-id]
    (let [exists (first (q/select-is-registered-to-other-exam-session spec {:participant_id  participant-id
                                                                            :exam_session_id exam-session-id}))]
      (:exists exists)))
  (get-started-registration-id+kind-by-participant-id
    [{:keys [spec]} participant-id exam-session-id]
    (first (q/select-started-registration-id-and-kind-by-participant spec {:participant_id  participant-id
                                                                           :exam_session_id exam-session-id})))
  (exam-session-space-left?
    [{:keys [spec]} exam-session-id registration-id]
    (let [exists (first (q/select-exam-session-space-left spec {:exam_session_id exam-session-id
                                                                :registration_id registration-id}))]
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
    [{:keys [spec]} registration after-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(when-let [update-success (int->boolean (q/update-registration-to-submitted! tx registration))]
           (after-fn)
           update-success))))
  (create-registration!
    [{:keys [spec]} registration]
    (jdbc/with-db-transaction [tx spec]
      (:id (q/insert-registration<! tx registration))))
  (update-started-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-started-registrations-to-expire tx)
                     (map :id))]
        (when (seq ids)
          (q/expire-registrations-by-ids! tx {:ids ids})
          ids))))
  (update-submitted-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-submitted-registrations-to-expire tx)
                     (map :id))]
        (when (seq ids)
          (q/expire-registrations-by-ids! tx {:ids ids})
          ids))))
  (get-participant-data-by-registration-id
    [{:keys [spec]} registration-id]
    (first (q/select-participant-data-by-registration-id spec {:id registration-id})))
  (get-registration
    [{:keys [spec]} registration-id external-user-id]
    (first (q/select-registration spec {:id registration-id :external_user_id external-user-id})))
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
                updated-registration    (q/complete-registration<! tx {:id registration-id})]
            (when (= "COMPLETED" (:state updated-registration))
              (after-fn updated-payment-details))
            updated-registration)))))
  (cancel-started-registration-for-participant! [{:keys [spec]} participant-id registration-id]
    (int->boolean
      (q/cancel-started-registration-for-participant!
        spec
        {:id             registration-id
         :participant_id participant-id})))
  (get-participant-and-queue-count-for-ongoing-admissions [{:keys [spec]}]
    (q/select-participant-and-queue-count-by-exam-session spec))
  (lift-registration-from-queue! [{:keys [spec]} exam-session-id send-email!]
    (jdbc/with-db-transaction [tx spec]
      ; TODO rollback-on-exception does not seem to reliably rollback changes!
      ; For instance, if an error is thrown when sending email,
      ; it appears that the registration will end up being lifted from queue.
      (rollback-on-exception
        tx
        (fn lift-registration-and-notify! []
          (let [registration (q/lift-registration-from-queue<! spec {:exam_session_id exam-session-id})]
            (send-email! registration))))))
  (expire-queued-registrations-after-exam-date! [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (let [ids (->> (q/select-queued-registrations-to-expire tx)
                     (map :id))]
        (when (seq ids)
          (q/expire-registrations-by-ids! tx {:ids ids})
          ids)))))
