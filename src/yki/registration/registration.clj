(ns yki.registration.registration
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
            [ring.util.http-response :refer [not-found]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [clojure.tools.logging :refer [info error]])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defn- get-participant-from-session [session]
  (let [identity (:identity session)]
    {:external_user_id (or (:ssn identity) (:external-user-id identity))
     :email (:external-user-id identity)}))

(defn get-participant-id
  [db session]
  (:id (registration-db/get-or-create-participant! db (get-participant-from-session session))))

(defn init-registration
  [db session registration-init]
  (let [participant-id (get-participant-id db session)
        registration (registration-db/create-registration! db (assoc registration-init
                                                                     :participant_id participant-id
                                                                     :started_at (t/now)))]
    (:id registration)))

    ; login-link
    ; :code hashed
    ; :participant_id participant-id
    ; :type "REGISTRATION"
    ; :email email
    ; :exam_session_id
    ; :expired_link_redirect
    ; :success_redirect
    ; :expires_at expires-at
    ; :registration_id registration-id
(defn create-secure-link [db url-helper email-q lang login-link expires-in-days]
  (let [code (str (UUID/randomUUID))
        login-url (str (url-helper :host-yki-oppija) "?code=" code)
        expires-at (t/plus (t/now) (t/days expires-in-days))
        hashed (sha256-hash code)]
    (login-link-db/create-login-link! db
                                      (assoc login-link
                                             :expires_at expires-at
                                             :code hashed))
    (pgq/put email-q
             {:recipients [(:email login-link)]
              :subject (template-util/subject "login_link" lang)
              :body (template-util/render "login_link" lang {:login-url login-url})})))

(defn submit-registration
  [db session id lang registration amount]
  (let [participant-id (get-participant-id db session)
        email (:email registration)]
    (when (and email (:ssn session))
      (registration-db/update-participant-email! db email participant-id))
    (let [payment {:registration_id id
                   :lang (or lang "fi")
                   :amount amount}
          update-registration {:id id
                               :participant_id participant-id}]
      (registration-db/create-payment-and-update-registration! db payment update-registration))))
