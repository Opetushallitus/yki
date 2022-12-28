(ns yki.boundary.login-link-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql])
  (:import (duct.database.sql Boundary)))

(require-sql ["yki/queries.sql" :as q])

(defprotocol LoginLinks
  (create-login-link! [db login-link])
  (get-login-link-by-code [db code])
  (get-login-link-by-exam-session-and-registration-id [db registration-id]))

(extend-protocol LoginLinks
  Boundary
  (create-login-link! [{:keys [spec]} login-link]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-login-link<! tx login-link)))
  (get-login-link-by-code [{:keys [spec]} code]
    (first (q/select-login-link-by-code spec {:code code})))
  (get-login-link-by-exam-session-and-registration-id [{:keys [spec]} registration-id]
    (first (q/select-login-link-by-exam-session-and-registration-id spec {:registration_id registration-id}))))
