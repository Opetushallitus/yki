(ns yki.boundary.registration-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol Registration
  (get-registration [db registration-id external-user-id])
  (create-participant-if-not-exists! [db external-user-id]))

(extend-protocol Registration
  duct.database.sql.Boundary
  (get-registration
    [{:keys [spec]} registration-id external-user-id]
    (q/select-registration spec {:id registration-id :external_user_id external-user-id}))
  (create-participant-if-not-exists!
    [{:keys [spec]} external-user-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-participant! tx {:external_user_id external-user-id}))))
