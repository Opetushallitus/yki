(ns yki.boundary.exam-session-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.coerce :as c]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.tools.logging :as log]
            [yki.boundary.db-extensions]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [exam-session]
  (reduce #(update-in %1 [%2] f/parse) exam-session [:session_date
                                                     :published_at]))

(defn- string->date [date]
  (if (some? date)
    (f/parse date)))

(defn rollback-on-exception [tx f]
  (try
    (f)
    (catch Exception e
      (.rollback (:connection tx))
      (log/error e "Execution failed. Rolling back transaction.")
      (throw e))))

(defprotocol ExamSessions
  (create-exam-session! [db oid exam-session send-to-queue-fn])
  (update-exam-session! [db oid id exam-session send-to-queue-fn])
  (delete-exam-session! [db id])
  (get-exam-session-by-id [db id])
  (get-exam-sessions [db oid from]
    "Get exam sessions by optional oid and from arguments"))

(extend-protocol ExamSessions
  duct.database.sql.Boundary
  (create-exam-session!
    [{:keys [spec]} oid exam-session send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [converted (merge {:office_oid nil} (assoc (convert-dates exam-session) :oid oid))
              result (q/insert-exam-session<! tx converted)
              exam-session-id (:id result)]
          (doseq [loc (:location exam-session)]
            (q/insert-exam-session-location! tx (assoc loc :exam_session_id exam-session-id)))
          (send-to-queue-fn exam-session-id)
          exam-session-id))))
  (update-exam-session!
    [{:keys [spec]} oid id exam-session send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(do
          (q/delete-exam-session-location! tx {:id id})
          (doseq [location (:location exam-session)]
            (q/insert-exam-session-location! tx (assoc location :exam_session_id id)))
          (q/update-exam-session! tx (merge {:office_oid nil} (assoc (convert-dates exam-session) :oid oid :id id)))
          (send-to-queue-fn id)))))
  (delete-exam-session! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-exam-session! tx {:id id})))
  (get-exam-session-by-id [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (first (q/select-exam-session-by-id tx {:id id}))))
  (get-exam-sessions [{:keys [spec]} oid from]
    (q/select-exam-sessions spec {:oid oid
                                  :from (string->date from)})))
