(ns yki.boundary.exam-payment-new-db
  (:require [jeesql.core :refer [require-sql]])
  (:import (duct.database.sql Boundary)))

(require-sql ["yki/queries.sql" :as q])

(defprotocol ExamPaymentNew
  (get-completed-payments-for-timerange [_ from-inclusive to-exclusive]))

(extend-protocol ExamPaymentNew
  Boundary
  (get-completed-payments-for-timerange [{:keys [spec]} from-inclusive to-exclusive]
    (q/select-completed-new-exam-payments-for-timerange
      spec
      {:from_inclusive from-inclusive
       :to_exclusive   to-exclusive})))
