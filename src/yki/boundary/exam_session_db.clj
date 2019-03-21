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

(defn- int->boolean [value]
  (= value 1))

(defn rollback-on-exception [tx f]
  (try
    (f)
    (catch Exception e
      (.rollback (:connection tx))
      (log/error e "Execution failed. Rolling back transaction.")
      (throw e))))

(defprotocol ExamSessions
  (create-exam-session! [db oid exam-session send-to-queue-fn])
  (update-exam-session! [db oid id exam-session])
  (delete-exam-session! [db id oid send-to-queue-fn])
  (init-participants-sync-status! [db exam-session-id])
  (set-participants-sync-to-success! [db exam-session-id])
  (set-participants-sync-to-failed! [db exam-session-id retry-duration])
  (set-registration-status-to-cancelled! [db registration-id oid])
  (update-registration-exam-session! [db to-exam-session-id registration-id oid])
  (get-exam-session-by-id [db id])
  (get-exam-session-by-registration-id [db registration-id])
  (get-exam-session-with-location [db id lang])
  (get-exam-session-participants [db id oid])
  (get-completed-exam-session-participants [db id])
  (get-exam-sessions-to-be-synced [db retry-duration])
  (get-exam-sessions [db oid from]
    "Get exam sessions by optional oid and from arguments")
  (get-exam-sessions-with-queue [db])
  (add-to-exam-session-queue! [db email lang exam-session-id])
  (update-exam-session-queue-last-notified-at! [db email exam-session-id])
  (remove-from-exam-session-queue! [db email exam-session-id]))

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
          (send-to-queue-fn)
          exam-session-id))))
  (init-participants-sync-status!
    [{:keys [spec]} exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-participants-sync-status! tx {:exam_session_id exam-session-id})))
  (set-participants-sync-to-success!
    [{:keys [spec]} exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-sync-to-success! tx {:exam_session_id exam-session-id})))
  (set-participants-sync-to-failed!
    [{:keys [spec]} exam-session-id interval]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-sync-to-failed! tx {:exam_session_id exam-session-id :interval interval})))
  (update-registration-exam-session!
    [{:keys [spec]} to-exam-session-id registration-id oid]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/update-registration-exam-session! tx {:exam_session_id to-exam-session-id
                                                             :registration_id registration-id
                                                             :oid oid}))))
  (set-registration-status-to-cancelled!
    [{:keys [spec]} registration-id oid]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/update-registration-status-to-cancelled! tx {:id registration-id :oid oid}))))
  (update-exam-session!
    [{:keys [spec]} oid id exam-session]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(do
          (q/delete-exam-session-location! tx {:id id})
          (doseq [location (:location exam-session)]
            (q/insert-exam-session-location! tx (assoc location :exam_session_id id)))
          (let [updated (int->boolean (q/update-exam-session!
                                       tx
                                       (merge {:office_oid nil} (assoc (convert-dates exam-session) :oid oid :id id))))]
            updated)))))
  (delete-exam-session! [{:keys [spec]} id oid send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
       tx
       #(let [deleted (int->boolean (q/delete-exam-session! tx {:id id :oid oid}))]
          (when deleted
            (send-to-queue-fn))
          deleted))))
  (get-exam-session-with-location [{:keys [spec]} id lang]
    (first (q/select-exam-session-with-location spec {:id id :lang lang})))
  (get-exam-session-by-id [{:keys [spec]} id]
    (first (q/select-exam-session-by-id spec {:id id})))
  (get-exam-session-by-registration-id [{:keys [spec]} registration-id]
    (first (q/select-exam-session-by-registration-id spec {:registration_id registration-id})))
  (get-exam-sessions-to-be-synced [{:keys [spec]} retry-duration]
    (q/select-exam-sessions-to-be-synced spec {:duration retry-duration}))
  (get-exam-session-participants [{:keys [spec]} id oid]
    (q/select-exam-session-participants spec {:id id :oid oid}))
  (get-completed-exam-session-participants [{:keys [spec]} id]
    (q/select-completed-exam-session-participants spec {:id id}))
  (get-exam-sessions [{:keys [spec]} oid from]
    (q/select-exam-sessions spec {:oid oid
                                  :from (string->date from)}))
  (get-exam-sessions-with-queue [{:keys [spec]}]
    (q/select-exam-sessions-with-queue spec))
  (add-to-exam-session-queue!
    [{:keys [spec]} email lang exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-exam-session-queue! tx {:exam_session_id exam-session-id
                                        :lang lang
                                        :email email})))
  (update-exam-session-queue-last-notified-at!
    [{:keys [spec]} email exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-exam-session-queue-last-notified-at! tx {:exam_session_id exam-session-id
                                                         :email email})))
  (remove-from-exam-session-queue!
    [{:keys [spec]} email exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-from-exam-session-queue! tx {:exam_session_id exam-session-id
                                        :email email}))))
