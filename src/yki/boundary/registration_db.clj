(ns yki.boundary.registration-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (get-next-order-number-suffix! [db])
  (get-payment-by-registration-id [db registration-id])
  (create-payment! [db payment])
  (complete-registration-and-payment! [db payment-params])
  (get-registration [db registration-id external-user-id])
  (create-participant-if-not-exists! [db external-user-id]))

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
      (q/update-registration! tx {:order_number order-number
                                  :state "COMPLETED"})))
  (get-next-order-number-suffix!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (:nextval (first (q/select-next-order-number-suffix tx)))))
  (create-payment!
    [{:keys [spec]} payment]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-payment<! tx payment)))
  (get-registration
    [{:keys [spec]} registration-id external-user-id]
    (q/select-registration spec {:id registration-id :external_user_id external-user-id}))
  (create-participant-if-not-exists!
    [{:keys [spec]} external-user-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-participant! tx {:external_user_id external-user-id}))))
