(ns yki.boundary.exam-date-db
  (:require
   [jeesql.core :refer [require-sql]]
   [clojure.java.jdbc :as jdbc]
   [clj-time.format :as f]
   [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol ExamDate
  (get-exam-dates [db])
  (delete-post-admission-end-date! [db exam-date-id])
  (update-post-admission-end-date! [db exam-date-id new-end-date]))

(extend-protocol ExamDate
  duct.database.sql.Boundary
  (get-exam-dates [{:keys [spec]}]
    (q/select-exam-dates spec))
  (delete-post-admission-end-date! [{:keys [spec]} exam-date-id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-post-admission-end-date! tx {:exam_date_id exam-date-id})))
  (update-post-admission-end-date! [{:keys [spec]} exam-date-id new-end-date]
    (jdbc/with-db-transaction [tx spec]
      (q/update-post-admission-end-date! tx {:exam_date_id exam-date-id :post_admission_end_date (f/parse new-end-date)}))))
