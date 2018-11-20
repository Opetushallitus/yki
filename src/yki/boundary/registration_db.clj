(ns yki.boundary.registration-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (get-next-order-number-suffix! [db])
  (get-payment-by-registration-id [db registration-id])
  (get-participant [db participant-query])
  (participant-allowed-to-register? [db participant-id])
  (create-payment! [db payment])
  (create-payment-and-update-registration! [db payment registration after-fn])
  (create-registration! [db registration])
  (get-registration-data [db registration-id participant-id lang])
  (complete-registration-and-payment! [db payment-params])
  (exam-session-has-space? [db exam-session-id])
  (update-participant-email! [db email participant-id])
  (get-participant-email-by-order-number [db order-number])
  (get-registration [db registration-id external-user-id])
  (get-or-create-participant! [db participant]))

(extend-protocol Registration
  duct.database.sql.Boundary
  (get-payment-by-registration-id [{:keys [spec]} registration-id]
    (first (q/select-payment-by-registration-id spec {:registration_id registration-id})))
  (complete-registration-and-payment!
    [{:keys [spec]} {:keys [order-number payment-id payment-method timestamp reference-number]}]
    (jdbc/with-db-transaction [tx spec]
      (q/update-payment! tx {:order_number order-number
                             :external_payment_id payment-id
                             :payment_method payment-method
                             :payed_at timestamp
                             :reference_number reference-number
                             :state "PAID"})
      (q/update-registration-to-completed! tx {:order_number order-number})))
  (get-participant
    [{:keys [spec]} participant-query]
    (first (q/select-participant spec participant-query)))
  (participant-allowed-to-register?
    [{:keys [spec]} participant-id]
    (let [result (first (q/select-participant-already-registered spec {:participant_id participant-id}))]
      (not (= (:count result) 1))))
  (get-participant-email-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-participant-email-by-order-number spec {:order_number order-number})))
  (get-next-order-number-suffix!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (:nextval (first (q/select-next-order-number-suffix tx)))))
  (exam-session-has-space? [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec {:id id}]
      (let [result (first (q/select-exam-session-full tx {:id id}))]
        (not (= (:case result) 1)))))
  (create-payment!
    [{:keys [spec]} payment]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-payment<! tx payment)))
  (update-participant-email!
    [{:keys [spec]} email participant-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-payment<! tx email participant-id)))
  (create-payment-and-update-registration!
    [{:keys [spec]} payment registration after-fn]
    (jdbc/with-db-transaction [tx spec]
      (let [order-number-suffix (:nextval (first (q/select-next-order-number-suffix tx)))
            order-number (str "YKI" order-number-suffix)]
        (q/update-registration-to-submitted! tx registration)
        (q/insert-payment<! tx (assoc payment :order_number order-number))
        (after-fn))))
  (create-registration!
    [{:keys [spec]} registration]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-registration<! tx registration)))
  (get-participant-email-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-participant-email-by-order-number spec {:order_number order-number})))
  (get-registration
    [{:keys [spec]} registration-id external-user-id]
    (first (q/select-registration spec {:id registration-id :external_user_id external-user-id})))
  (get-registration-data
    [{:keys [spec]} registration-id participant-id lang]
    (first (q/select-registration-data spec {:id registration-id :participant_id participant-id :lang lang})))
  (get-or-create-participant!
    [{:keys [spec]} participant]
    (jdbc/with-db-transaction [tx spec]
      (if-let [participant (first (q/select-participant tx participant))]
        participant
        (q/insert-participant<! tx participant)))))
