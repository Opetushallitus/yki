(ns yki.boundary.login-link-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clj-time.format :as f]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [login-link]
  (update login-link :expires_at f/parse))

(defprotocol LoginLinks
  (create-login-link! [db login-link])
  (get-login-link-by-code [db code]))

(extend-protocol LoginLinks
  duct.database.sql.Boundary
  (create-login-link! [{:keys [spec]} login-link]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-login-link<! tx (convert-dates login-link))))
  (get-login-link-by-code [{:keys [spec]} code]
    (first (q/select-login-link-by-code spec {:code code}))))
