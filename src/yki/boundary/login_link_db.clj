(ns yki.boundary.login-link-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol LoginLinks
  (get-login-link-by-code [db code]))

(extend-protocol LoginLinks
  duct.database.sql.Boundary
  (get-login-link-by-code [{:keys [spec]} code]
    (jdbc/with-db-transaction [tx spec]
      (first (q/select-login-link-by-code tx {:code code})))))
