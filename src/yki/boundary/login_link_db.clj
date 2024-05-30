(ns yki.boundary.login-link-db
  (:require
    [clojure.java.jdbc :as jdbc]
    [duct.database.sql]
    [jeesql.core :refer [require-sql]]
    [yki.boundary.db-extensions])
  (:import (duct.database.sql Boundary)))

(require-sql ["yki/queries.sql" :as q])

(defprotocol LoginLinks
  (create-login-link! [db login-link])
  (get-recent-login-link-by-exam-session-and-participant [db exam-session-id participant-id older-than])
  (get-login-link-by-code [db code])
  (get-login-link-by-exam-session-and-registration-id [db registration-id]))

(extend-protocol LoginLinks
  Boundary
  (create-login-link! [{:keys [spec]} login-link]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-login-link<! tx login-link)))
  (get-recent-login-link-by-exam-session-and-participant [{:keys [spec]} exam-session-id participant-id older-than]
    (first
      (q/select-recent-login-link-by-exam-session-and-participant-id
        spec
        {:exam_session_id exam-session-id
         :participant_id  participant-id
         :older_than      older-than})))
  (get-login-link-by-code [{:keys [spec]} code]
    (first (q/select-login-link-by-code spec {:code code})))
  (get-login-link-by-exam-session-and-registration-id [{:keys [spec]} registration-id]
    (first (q/select-login-link-by-exam-session-and-registration-id spec {:registration_id registration-id}))))
