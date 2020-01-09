(ns yki.boundary.post-admission-db
  (:require [clj-time.format :as f]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [entity date-keys]
  (reduce #(update-in %1 [%2] f/parse) entity date-keys))

(defprotocol PostAdmission
  (upsert-post-admission [db post-admission exam-session-id]))

(extend-protocol PostAdmission
  duct.database.sql.Boundary
  (upsert-post-admission [{:keys [spec]} post-admission exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/upsert-post-admission! 
       spec 
       (into {:exam_session_id exam-session-id} 
             (-> post-admission
                 (convert-dates [:start_date :end_date])))))))
