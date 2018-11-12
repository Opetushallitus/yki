(ns yki.auth.code-auth
  (:require [ring.util.http-response :refer [found]]
            [yki.boundary.login-link-db :as login-link-db]
            [clojure.tools.logging :refer [info error]]
            [yki.handler.login-link :as login-link]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure.string :as str])
  (:import [java.util UUID]
           [org.slf4j MDC]))

(def unauthorized {:status 401
                   :body "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- link-valid? [expires]
  (t/after? expires (l/local-now)))

(defn login [db code lang url-helper]
  (try
    (if-let [login-link (login-link-db/get-login-link-by-code db (login-link/sha256-hash code))]
      (if (link-valid? (:expires_at login-link))
        (assoc
         (found (:success_redirect login-link))
         :session
         {:identity {:external-user-id (:external_user_id login-link)},
          :yki-session-id (str (UUID/randomUUID))})
        (found (:expired_link_redirect login-link)))
      unauthorized)
    (catch Exception e
      (error e "Login link handling failed")
      (throw e))))
