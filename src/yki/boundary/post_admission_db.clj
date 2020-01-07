(ns yki.boundary.post-admission-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol PostAdmission
  (upsert-post-admission [db post-admission exam-session-id]))

(extend-protocol PostAdmission
  duct.database.sql.Boundary
  (upsert-post-admission [{:keys [spec]} post-admission exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (println "tx" tx)
      (println "spec" spec)
      (println "post-admission" post-admission)
      (q/upsert-post-admission! 
       spec 
       (into {:exam_session_id exam-session-id} (select-keys post-admission [:start_date :end_date :quota]))))))
