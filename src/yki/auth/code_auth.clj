(ns yki.auth.code-auth
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure.tools.logging :refer [error]]
            [ring.util.http-response :refer [found ok not-found]]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.handler.login-link :as login-link]))

(def unauthorized {:status  401
                   :body    "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- link-valid? [{:keys [expires_at]}]
  (t/after? expires_at (l/local-now)))

(defn login [db code _lang _url-helper]
  (try
    (if-let [login-link (login-link-db/get-login-link-by-code db (login-link/sha256-hash code))]
      (if (link-valid? login-link)
        (assoc
          (found (:success_redirect login-link))
          :session
          {:identity     (merge {:external-user-id (:external_user_id login-link)
                                 :email            (:email            login-link)}
                                 (when-let [oid    (:person_oid       login-link)]
                                   {:oid oid})
                                 (when-let [previous-session-id (get-in login-link [:user_data :previous-session-id])]
                                   {:previous-session-id previous-session-id}))
           :auth-method "EMAIL"
           :yki-session-id (str (random-uuid))})
        (found (:expired_link_redirect login-link)))
      unauthorized)
    (catch Exception e
      (error e "Login link handling failed")
      (throw e))))

(defn logout [redirect-url]
  (-> (found redirect-url)
      (assoc :session nil)))

(defn get-link-details [db code]
  (if-let [link (login-link-db/get-login-link-by-code db (login-link/sha256-hash code))]
    (if (link-valid? link)
      (ok (select-keys link [:expires_at :success_redirect]))
      (ok (select-keys link [:expires_at :expired_link_redirect])))
    (not-found)))
