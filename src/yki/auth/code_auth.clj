(ns yki.auth.code-auth
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure.tools.logging :refer [error]]
            [ring.util.http-response :refer [found]]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.handler.login-link :as login-link]))

(def unauthorized {:status 401
                   :body "Unauthorized"
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
         {:identity {:external-user-id (:external_user_id login-link)}
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
