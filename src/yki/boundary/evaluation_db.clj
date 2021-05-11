(ns yki.boundary.evaluation-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.jdbc]
            [yki.boundary.db-extensions]
            [duct.database.sql]
            [clojure.java.jdbc :as jdbc]
            [clj-time.format :as f]
            [clojure.tools.logging :as log]))

(require-sql ["yki/queries.sql" :as q])

(defn rollback-on-exception [tx f]
  (try
    (f)
    (catch Exception e
      (.rollback (:connection tx))
      (log/error e "Execution failed. Rolling back transaction.")
      (throw e))))

(defn- string->date [date]
  (if (some? date)
    (f/parse date)))

(defprotocol Evaluations
  (get-upcoming-evaluation-periods [db])
  (get-evaluation-period-by-id [db id])
  (get-evaluation-periods-by-exam-date-id [db exam-date-id])
  (get-evaluation-order-by-id [db id])
  (get-evaluation-order-with-payment [db id])
  (get-finished-evaluation-order-by-id [db id])
  (get-subtests [db])
  (get-payment-by-order-number [db order-number])
  (get-order-data-by-order-number [db order-number])
  (create-evaluation-order! [db evaluation-id evaluation-order])
  (create-evaluation! [db exam-date-languages evaluation])
  (create-evaluation-payment! [db payment])
  (complete-payment! [db payment-params]))

(defn- int->boolean [value]
  (pos? value))

(extend-protocol Evaluations
  duct.database.sql.Boundary
  (get-upcoming-evaluation-periods [{:keys [spec]}]
    (q/select-upcoming-evaluation-periods spec))
  (get-evaluation-period-by-id [{:keys [spec]} id]
    (first (q/select-evaluation-by-id spec {:evaluation_id id})))
  (get-evaluation-periods-by-exam-date-id [{:keys [spec]} exam-date-id]
    (q/select-evaluations-by-exam-date-id spec {:exam_date_id exam-date-id}))
  (get-evaluation-order-by-id [{:keys [spec]} id]
    (first (q/select-evaluation-order-by-id spec {:evaluation_order_id id})))
  (get-evaluation-order-with-payment [{:keys [spec]} id]
    (first (q/select-evaluation-order-with-payment spec {:evaluation_order_id id})))
  (get-subtests [{:keys [spec]}]
    (q/select-subtests spec))
  (get-payment-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-evaluation-payment-by-order-number spec {:order_number order-number})))
  (get-order-data-by-order-number
    [{:keys [spec]} order-number]
    (first (q/select-evaluation-order-data-by-order-number spec {:order_number order-number})))
  (create-evaluation!
    [{:keys [spec]} exam-date-languages evaluation]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [start-date (string->date (:evaluation_start_date evaluation))
              end-date (string->date (:evaluation_end_date evaluation))]
          (doseq [edl exam-date-languages]
            (q/insert-evaluation! tx {:exam_date_id (:exam_date_id edl)
                                      :exam_date_language_id (:id edl)
                                      :evaluation_start_date (string->date (:evaluation_start_date evaluation))
                                      :evaluation_end_date (string->date (:evaluation_end_date evaluation))}))

          true))))
  (create-evaluation-order!
    [{:keys [spec]} evaluation-id evaluation-order]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [result (q/insert-evaluation-order<! tx (assoc evaluation-order :evaluation_id evaluation-id))
              evaluation-order-id (:id result)]
          (doseq [subtest (:subtests evaluation-order)]
            (q/insert-evaluation-order-subtest! tx {:subtest subtest
                                                    :evaluation_order_id evaluation-order-id}))
          evaluation-order-id))))
  (create-evaluation-payment!
    [{:keys [spec]} payment]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [order-number-seq (:nextval (first (q/select-next-evaluation-order-number-suffix tx)))
              order-number (str "YKI-EVAL" (format "%09d" order-number-seq))]
          (q/insert-initial-evaluation-payment<! tx (assoc payment :order_number order-number))))))
  (complete-payment!
    [{:keys [spec]} {:keys [order-number payment-id payment-method timestamp reference-number]}]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/update-evaluation-payment! tx {:order_number order-number
                                                      :external_payment_id payment-id
                                                      :payment_method payment-method
                                                      :payed_at timestamp
                                                      :reference_number reference-number
                                                      :state "PAID"})))))
