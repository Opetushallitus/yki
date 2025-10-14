(ns yki.auth.code-auth
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure.string :as str]
            [clojure.tools.logging :refer [error]]
            [ring.util.http-response :refer [found ok not-found]]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.handler.login-link :as login-link]))

(def unauthorized {:status  401
                   :body    "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- link-valid? [{:keys [expires_at]}]
  (t/after? expires_at (l/local-now)))

(defn login [db code _lang url-helper]
  (try
    (if-let [login-link (login-link-db/get-login-link-by-code db (login-link/sha256-hash code))]
      (if (link-valid? login-link)
        (let [previous-session-id (get-in login-link [:user_data :previous-session-id])
              oid                 (:person_oid login-link)
              registration-id     (:registration_id login-link)
              email               (or (:person_email login-link) (:participant_email login-link))
              identity            (cond-> {:external-user-id (:external_user_id login-link)
                                           :email            email}
                                          previous-session-id
                                          (assoc :previous-session-id previous-session-id)
                                          oid
                                          (assoc :oid oid)
                                          registration-id
                                          (assoc :registration-id registration-id))
              session             {:identity       identity
                                   :auth-method    "EMAIL"
                                   :auth-target    (:type login-link)
                                   :yki-session-id (str (random-uuid))}]
          (assoc
            (found (:success_redirect login-link))
            :session session))
        (found (str/replace (:expired_link_redirect login-link) ":code" code)))
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
