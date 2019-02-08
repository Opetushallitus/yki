(ns yki.boundary.registration-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :refer [error]]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (get-next-order-number-suffix! [db])
  (get-payment-by-registration-id [db registration-id])
  (get-payment-by-order-number [db order-number])
  (get-participant-by-id [db id])
  (get-participant-by-external-id [db external-id])
  (not-registered-to-another-exam-session? [db participant-id exam-session-id])
  (registration-id-by-participant [db participant-id exam-session-id])
  (create-payment-and-update-registration! [db payment registration after-fn])
  (create-registration! [db registration])
  (get-registration-data [db registration-id participant-id lang])
  (get-payment-config-by-order-number [db order-number])
  (complete-registration-and-payment! [db payment-params])
  (exam-session-space-left? [db exam-session-id])
  (exam-session-registration-open? [db exam-session-id])
  (update-participant-email! [db email participant-id])
  (get-participant-data-by-order-number [db order-number])
  (get-registration [db registration-id external-user-id])
  (get-or-create-participant! [db participant])
  (update-started-registrations-to-expired! [db])
  (update-submitted-registrations-to-expired! [db]))

(defn- int->boolean [value]
  (pos? value))

(extend-protocol Registration
  duct.database.sql.Boundary
  (get-payment-by-registration-id [{:keys [spec]} registration-id]
    (first (q/select-payment-by-registration-id spec {:registration_id registration-id})))
  (get-payment-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-payment-by-order-number spec {:order_number order-number})))
  (complete-registration-and-payment!
    [{:keys [spec]} {:keys [order-number payment-id payment-method timestamp reference-number]}]
    (jdbc/with-db-transaction [tx spec]
      (q/update-payment! tx {:order_number order-number
                             :external_payment_id payment-id
                             :payment_method payment-method
                             :payed_at timestamp
                             :reference_number reference-number
                             :state "PAID"})
      (int->boolean (q/update-registration-to-completed! tx {:order_number order-number}))))
  (get-participant-by-id
    [{:keys [spec]} id]
    (first (q/select-participant-by-id spec {:id id})))
  (get-payment-config-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-payment-config-by-order-number spec {:order_number order-number})))
  (get-participant-by-external-id
    [{:keys [spec]} external-id]
    (first (q/select-participant-by-external-id spec {:external_user_id external-id})))
  (not-registered-to-another-exam-session?
    [{:keys [spec]} participant-id exam-session-id]
    (let [exists (first (q/select-not-registered-to-another-exam-session spec {:participant_id participant-id
                                                                               :exam_session_id exam-session-id}))]
      (:exists exists)))
  (registration-id-by-participant
    [{:keys [spec]} participant-id exam-session-id]
    (:id (first (q/select-registration-id-by-participant spec {:participant_id participant-id
                                                               :exam_session_id exam-session-id}))))
  (get-next-order-number-suffix!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (:nextval (first (q/select-next-order-number-suffix tx)))))
  (exam-session-space-left?
    [{:keys [spec]} id]
    (let [exists (first (q/select-exam-session-space-left spec {:exam_session_id id}))]
      (:exists exists)))
  (exam-session-registration-open?
    [{:keys [spec]} id]
    (let [exists (first (q/select-exam-session-registration-open spec {:exam_session_id id}))]
      (:exists exists)))
  (update-participant-email!
    [{:keys [spec]} email participant-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-email! tx {:email email :id participant-id})))
  (create-payment-and-update-registration!
    [{:keys [spec]} payment registration after-fn]
    (jdbc/with-db-transaction [tx spec]
      (try
        (let [order-number-seq (:nextval (first (q/select-next-order-number-suffix tx)))
              oid-last-part (last (str/split (:oid registration) #"\."))
              order-number (str "YKI" oid-last-part (format "%09d" order-number-seq))
              update-success (int->boolean (q/update-registration-to-submitted! tx registration))]
          (when update-success
            (q/insert-payment<! tx (assoc payment :order_number order-number))
            (after-fn))
          update-success)
        (catch Exception e
          (.rollback (:connection tx))
          (error e "Create payment and update registration failed. Rolling back transaction")
          (throw e)))))
  (create-registration!
    [{:keys [spec]} registration]
    (jdbc/with-db-transaction [tx spec]
      (:id (q/insert-registration<! tx registration))))
  (update-started-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (q/update-started-registrations-to-expired<! tx)))
  (update-submitted-registrations-to-expired!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (q/update-submitted-registrations-to-expired<! tx)))
  (get-participant-data-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-participant-data-by-order-number spec {:order_number order-number})))
  (get-registration
    [{:keys [spec]} registration-id external-user-id]
    (first (q/select-registration spec {:id registration-id :external_user_id external-user-id})))
  (get-registration-data
    [{:keys [spec]} registration-id participant-id lang]
    (first (q/select-registration-data spec {:id registration-id :participant_id participant-id :lang lang})))
  (get-or-create-participant!
    [{:keys [spec]} participant]
    (jdbc/with-db-transaction [tx spec]
      (if-let [existing (first (q/select-participant-by-external-id tx participant))]
        existing
        (q/insert-participant<! tx participant)))))
