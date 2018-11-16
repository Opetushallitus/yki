(ns yki.registration.paytrail-payment-test
  (:require [clojure.test :refer :all]
            [yki.handler.base-test :as base]
            [duct.database.sql]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [jsonista.core :as j]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest create-payment
  (base/insert-login-link-prereqs)
  (base/insert-payment)
  (jdbc/execute! @embedded-db/conn "delete from payment")
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        _ (paytrail-payment/create-payment db base/payment-config 1 "test@user.com" "fi")
        result (first (jdbc/query @embedded-db/conn "SELECT * FROM payment"))]
    (testing "should create payment"
      (is (= (:state result) "UNPAID"))
      (is (= (:order_number result) "YKIcom1")))))

