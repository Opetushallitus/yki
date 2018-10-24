(ns yki.boundary.registration-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (create-participant-if-not-exists! [db external-id]))

(extend-protocol Registration
  duct.database.sql.Boundary
  (create-participant-if-not-exists!
    [{:keys [spec]} external-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-participant! tx {:external_user_id external-id}))))
