(ns yki.boundary.exam-session-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.coerce :as c]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [yki.util.db-util]
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

(defprotocol ExamSessions
  (create-exam-session! [db exam-session]))

(extend-protocol ExamSessions
  duct.database.sql.Boundary
  (create-exam-session!
    [{:keys [spec]} exam-session]
    (jdbc/with-db-transaction [tx spec]
      (let [result (q/insert-exam-session<! tx (convert-dates exam-session))
            exam-session-id (result :id)]
        (doseq [loc (:location exam-session)]
          (-> (q/insert-exam-session-location! tx (assoc loc :exam_session_id exam-session-id)))) exam-session-id))))
