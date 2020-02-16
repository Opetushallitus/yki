(ns yki.boundary.login-link-db
  (:require [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [clj-time.format :as f]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

(require-sql ["yki/queries.sql" :as q])

(defprotocol LoginLinks
  (create-login-link! [db login-link])
  (get-login-link-by-code [db code])
  (get-login-link-by-exam-session-and-registration-id [db exam-session-id registration-id]))

(extend-protocol LoginLinks
  duct.database.sql.Boundary
  (create-login-link! [{:keys [spec]} login-link]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-login-link<! tx login-link)))
  (get-login-link-by-code [{:keys [spec]} code]
    (first (q/select-login-link-by-code spec {:code code})))
  (get-login-link-by-exam-session-and-registration-id [{:keys [spec]} exam-session-id registration-id]
    (first (q/select-login-link-by-exam-session-and-registration-id spec {:exam_session_id exam-session-id :registration_id registration-id}))))
