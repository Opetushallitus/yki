(ns yki.boundary.exam-date-db
  (:require
   [clojure.java.jdbc :as jdbc]
   [duct.database.sql]
   [jeesql.core :refer [require-sql]]
   [yki.util.common :refer [string->date]]
   [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol ExamDate
  (create-exam-date! [db exam-date])
  (update-exam-date! [db id exam-date-update new-languages removable-languages])
  (get-exam-dates [db])
  (get-organizer-exam-dates [db from])
  (get-exam-date-by-id [db id])
  (get-exam-date-session-count [db id])
  (get-exam-dates-by-date [db exam-date])
  (get-exam-date-languages [db id])
  (delete-exam-date! [db id]))

(extend-protocol ExamDate
  Boundary
  (create-exam-date!
    [{:keys [spec]} exam-date]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [result (q/insert-exam-date<! tx {:exam_date              (string->date (:exam_date exam-date))
                                               :registration_start_date (string->date (:registration_start_date exam-date))
                                               :registration_end_date   (string->date (:registration_end_date exam-date))})
               id (:id result)]
          (doseq [lang (:languages exam-date)]
            (q/insert-exam-date-language! tx (assoc lang :exam_date_id id)))
          id))))
  (update-exam-date!
    [{:keys [spec]} id exam-date-update new-languages removable-languages]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(do (q/update-exam-date! tx {:id                        id
                                      :exam_date                 (string->date (:exam_date exam-date-update))
                                      :registration_start_date   (string->date (:registration_start_date exam-date-update))
                                      :registration_end_date     (string->date (:registration_end_date exam-date-update))
                                      :post_admission_start_date (string->date (:post_admission_start_date exam-date-update))
                                      :post_admission_end_date   (string->date (:post_admission_end_date exam-date-update))
                                      :post_admission_enabled    (:post_admission_enabled exam-date-update)})
             (doseq [lang new-languages]
               (q/insert-exam-date-language! tx (assoc lang :exam_date_id id)))
             (doseq [lang removable-languages]
                (q/delete-exam-date-language! tx {:exam_date_id id
                                                :level_code (:level_code lang)
                                                :language_code (:language_code lang)}))
             true))))
  (get-exam-date-by-id [{:keys [spec]} id]
    (first (q/select-exam-date-by-id spec {:id id})))
  (get-exam-dates [{:keys [spec]}]
    (q/select-exam-dates spec))
  (get-organizer-exam-dates [{:keys [spec]} from]
    (q/select-organizer-exam-dates spec {:from (string->date from)}))
  (get-exam-date-session-count [{:keys [spec]} id]
    (first (q/select-exam-date-session-count spec {:id id})))
  (get-exam-dates-by-date [{:keys [spec]} exam-date]
    (q/select-exam-dates-by-date spec {:exam_date exam-date}))
  (get-exam-date-languages [{:keys [spec]} id]
    (q/select-exam-date-languages spec {:exam_date_id id}))
  (delete-exam-date! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-exam-date! tx {:id id})
      (q/delete-exam-date-languages! tx {:exam_date_id id}))))
