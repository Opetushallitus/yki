(ns yki.boundary.payment-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Payment
  (get-next-order-number-suffix! [db])
  (get-payment-by-registration-id [db registration-id])
  (create-payment! [db payment]))

(extend-protocol Payment
  duct.database.sql.Boundary
  (get-payment-by-registration-id [{:keys [spec]} registration-id]
    (first (q/select-payment-by-registration-id spec {:registration_id registration-id})))
  (get-next-order-number-suffix!
    [{:keys [spec]}]
    (jdbc/with-db-transaction [tx spec]
      (q/select-next-order-number-suffix! tx)))
  (create-payment!
    [{:keys [spec]} payment]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-payment<! tx payment))))
