(ns yki.boundary.exam-date-db
  (:require
   [clj-time.format :as f]
   [clojure.java.jdbc :as jdbc]
   [duct.database.sql]
   [jeesql.core :refer [require-sql]]
   [yki.util.common :refer [string->date]]
   [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [exam-date]
  (reduce #(update-in %1 [%2] f/parse) exam-date [:exam_date
                                                  :registration_start_date
                                                  :registration_end_date]))

(defprotocol ExamDate
  (create-exam-date! [db exam-date])
  (create-exam-date-languages! [db exam-date-id languages])
  (get-exam-dates [db])
  (get-organizer-exam-dates [db from])
  (get-exam-date-by-id [db id])
  (get-exam-date-session-count [db id])
  (get-exam-dates-by-date [db exam-date])
  (get-exam-date-language [db id language])
  (get-exam-date-languages [db id])
  (delete-exam-date! [db id])
  (delete-exam-date-languages! [db id languages])
  (create-and-delete-exam-date-languages! [db id new-languages removed-languages])
  (update-post-admission-details! [db id post-admission])
  (toggle-post-admission! [db id enabled]))

(extend-protocol ExamDate
  Boundary
  (create-exam-date!
    [{:keys [spec]} exam-date]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [converted-dates (convert-dates exam-date)
              result (q/insert-exam-date<! tx converted-dates)
              exam-date-id (:id result)]
          (doseq [lang (:languages exam-date)]
            (q/insert-exam-date-language! tx (assoc lang :exam_date_id exam-date-id)))
          exam-date-id))))
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
  (get-exam-date-language [{:keys [spec]} id language]
    (q/select-exam-date-language spec {:exam_date_id id
                                       :level_code (:level_code language)
                                       :language_code (:language_code language)}))
  (get-exam-date-languages [{:keys [spec]} id]
    (q/select-exam-date-languages spec {:exam_date_id id}))
  (update-post-admission-details!
    [{:keys [spec]} id post-admission]
    (jdbc/with-db-transaction [tx spec]
      (q/update-exam-date-post-admission-details! tx {:id id
                                                      :post_admission_start_date (string->date (:post_admission_start_date post-admission))
                                                      :post_admission_end_date (string->date (:post_admission_end_date post-admission))
                                                      :post_admission_enabled (:post_admission_enabled post-admission)})))
  (toggle-post-admission!
    [{:keys [spec]} id enabled]
    (jdbc/with-db-transaction [tx spec]
      (q/update-exam-date-post-admission-status! tx {:id id
                                                     :post_admission_enabled enabled})))
  (delete-exam-date! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-exam-date! tx {:id id})
      (q/delete-exam-date-languages! tx {:exam_date_id id})))

  (delete-exam-date-languages! [{:keys [spec]} id languages]
    (jdbc/with-db-transaction [tx spec]
      (doseq [lang languages]
        (q/delete-exam-date-language! tx {:exam_date_id id
                                          :level_code (:level_code lang)
                                          :language_code (:language_code lang)}))
      true))

  (create-exam-date-languages!
    [{:keys [spec]} exam-date-id languages]
    (jdbc/with-db-transaction [tx spec]
      (doseq [lang languages]
        (q/insert-exam-date-language! tx (assoc lang :exam_date_id exam-date-id)))
      true))

  (create-and-delete-exam-date-languages!
    [{:keys [spec]} exam-date-id added-languages removed-languages]
    (jdbc/with-db-transaction [tx spec]
      (doseq [lang added-languages]
        (q/insert-exam-date-language! tx (assoc lang :exam_date_id exam-date-id)))
      (doseq [lang removed-languages]
        (q/delete-exam-date-language! tx {:exam_date_id exam-date-id
                                          :level_code (:level_code lang)
                                          :language_code (:language_code lang)}))
      true)))
