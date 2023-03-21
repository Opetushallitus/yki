(ns yki.boundary.evaluation-db
  (:require
    [clj-time.jdbc]
    [clojure.java.jdbc :as jdbc]
    [duct.database.sql]
    [jeesql.core :refer [require-sql]]
    [yki.boundary.db-extensions]
    [yki.util.common :refer [string->date]]
    [yki.util.db :refer [rollback-on-exception]]
    [yki.util.evaluation-payment-helper :refer [insert-initial-payment-data!]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Evaluations
  (get-upcoming-evaluation-periods [db])
  (get-evaluation-period-by-id [db id])
  (get-evaluation-periods-by-exam-date-id [db exam-date-id])
  (get-evaluation-order-by-id [db id])
  (get-new-payment-by-transaction-id [db transaction-id])
  (create-evaluation-order! [db evaluation-id evaluation-order])
  (create-evaluation! [db exam-date-languages evaluation])
  (create-evaluation-payment! [db payment-helper payment use-new-yki-ui?])
  (complete-new-payment! [db payment-id]))

(extend-protocol Evaluations
  Boundary
  (get-upcoming-evaluation-periods [{:keys [spec]}]
    (q/select-upcoming-evaluation-periods spec))
  (get-evaluation-period-by-id [{:keys [spec]} id]
    (first (q/select-evaluation-by-id spec {:evaluation_id id})))
  (get-evaluation-periods-by-exam-date-id [{:keys [spec]} exam-date-id]
    (q/select-evaluations-by-exam-date-id spec {:exam_date_id exam-date-id}))
  (get-evaluation-order-by-id [{:keys [spec]} id]
    (first (q/select-evaluation-order-by-id spec {:evaluation_order_id id})))
  (get-new-payment-by-transaction-id
    [{:keys [spec]} transaction-id]
    (first (q/select-evaluation-payment-new-by-transaction-id spec {:transaction_id transaction-id})))
  (create-evaluation!
    [{:keys [spec]} exam-date-languages evaluation]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [start-date (string->date (:evaluation_start_date evaluation))
               end-date   (string->date (:evaluation_end_date evaluation))]
           (doseq [edl exam-date-languages]
             (q/insert-evaluation! tx {:exam_date_id          (:exam_date_id edl)
                                       :exam_date_language_id (:id edl)
                                       :evaluation_start_date start-date
                                       :evaluation_end_date   end-date}))
           true))))
  (create-evaluation-order!
    [{:keys [spec]} evaluation-id evaluation-order]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [result              (q/insert-evaluation-order<! tx (assoc evaluation-order :evaluation_id evaluation-id))
               evaluation-order-id (:id result)]
           (doseq [subtest (:subtests evaluation-order)]
             (q/insert-evaluation-order-subtest! tx {:subtest             subtest
                                                     :evaluation_order_id evaluation-order-id}))
           evaluation-order-id))))
  (create-evaluation-payment!
    [{:keys [spec]} payment-helper payment use-new-yki-ui?]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [order-number-seq (:nextval (first (q/select-next-evaluation-order-number-suffix tx)))
               unix-ts-substr   (-> (System/currentTimeMillis)
                                    (quot 1000)
                                    (str)
                                    (subs 4))
               order-number     (str "YKI-EVAL" unix-ts-substr (format "%09d" order-number-seq))]
           (insert-initial-payment-data! payment-helper tx (assoc payment :order_number order-number) use-new-yki-ui?)))))
  (complete-new-payment!
    [{:keys [spec]} payment-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-new-evaluation-payment-to-paid<!
        tx
        {:id payment-id}))))
