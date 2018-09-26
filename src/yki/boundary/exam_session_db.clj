(ns yki.boundary.exam-session-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.coerce :as c]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [yki.boundary.db-extensions]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [{:keys [organizer_oid session_date session_start_time session_end_time
                              registration_start_date registration_start_time registration_end_date
                              registration_end_time max_participants published_at]}]
  {:organizer_oid organizer_oid
   :session_date (f/parse session_date)
   :session_start_time (f/parse session_start_time)
   :session_end_time (f/parse session_end_time)
   :registration_start_date (f/parse registration_start_date)
   :registration_start_time (f/parse registration_start_time)
   :registration_end_date (f/parse registration_end_date)
   :registration_end_time (f/parse registration_end_time)
   :max_participants max_participants
   :published_at (f/parse published_at)})

(defn- string->date [date]
  (if (some? date)
    (f/parse date)))

(defprotocol ExamSessions
  (create-exam-session! [db oid exam-session])
  (update-exam-session! [db id exam-session])
  (delete-exam-session! [db id])
  (get-exam-sessions [db oid from]
    "Get exam sessions by optional oid and optional from arguments"))

(extend-protocol ExamSessions
  duct.database.sql.Boundary
  (create-exam-session!
    [{:keys [spec]} oid exam-session]
    (jdbc/with-db-transaction [tx spec]
      (let [result (q/insert-exam-session<! tx (assoc (convert-dates exam-session) :oid oid))
            exam-session-id (result :id)]
        (doseq [loc (:location exam-session)]
          (q/insert-exam-session-location! tx (assoc loc :exam_session_id exam-session-id)))
        exam-session-id)))
  (update-exam-session!
    [{:keys [spec]} id exam-session]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-exam-session-location! tx {:id id})
      (doseq [location (:location exam-session)]
        (q/insert-exam-session-location! tx (assoc location :exam_session_id id)))
      (q/update-exam-session! tx (assoc (convert-dates exam-session) :id id))))
  (delete-exam-session! [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-exam-session! tx {:id id})))
  (get-exam-sessions [{:keys [spec]} oid from]
    (q/select-exam-sessions spec {:oid oid
                                  :from (string->date from)})))
