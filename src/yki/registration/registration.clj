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

(defn- get-external-id-from-session [session]
  (let [identity (:identity session)]
    {:external_user_id (or (:ssn identity) (:external-user-id identity))
     :id nil}))

(defn get-participant-id
  [db session]
  (:id (registration-db/get-participant db (get-external-id-from-session session))))

(defn get-or-create-participant
  [db session]
  (:id (registration-db/get-or-create-participant! db (get-external-id-from-session session))))

(defn init-registration
  [db session registration-init]
  (let [participant-id (get-participant-id db session)
        registration (registration-db/create-registration! db (assoc registration-init
                                                                     :participant_id participant-id
                                                                     :started_at (t/now)))]
    (:id registration)))

(defn create-secure-link [db url-helper email-q lang login-link expires-in-days]
  (let [code (str (UUID/randomUUID))
        login-url (str (url-helper :host-yki-oppija) "?code=" code)
        expires-at (t/plus (t/now) (t/days expires-in-days))
        email (:email (registration-db/get-participant db {:id (:participant_id login-link)
                                                           :external_user_id nil}))
        link-type (:type login-link)
        hashed (sha256-hash code)]
    (login-link-db/create-login-link! db
                                      (assoc login-link
                                             :expires_at expires-at
                                             :code hashed))
    (pgq/put email-q
             {:recipients [email]
              :subject (template-util/subject link-type lang)
              :body (template-util/render link-type lang {:login-url login-url})})))

(defn submit-registration
  [db url-helper email-q lang session id registration amount]
  (let [participant-id (get-participant-id db session)
        email (:email registration)]
    (when (and email (:ssn session))
      (registration-db/update-participant-email! db email participant-id))
    (let [payment {:registration_id id
                   :lang lang
                   :amount amount}
          update-registration {:id id
                               :participant_id participant-id}
          login-link {:participant_id participant-id
                      :exam_session_id nil
                      :registration_id id
                      :expired_link_redirect (url-helper :payment-link.redirect)
                      :success_redirect (url-helper :link-expired.redirect)
                      :type "PAYMENT"}]
      (registration-db/create-payment-and-update-registration! db payment update-registration)
      (create-secure-link db url-helper email-q lang login-link 8))))
