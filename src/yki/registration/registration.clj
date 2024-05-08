(ns yki.registration.registration
  (:require [buddy.core.codecs :refer [bytes->hex]]
            [buddy.core.hash :as hash]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [pgqueue.core :as pgq]
            [ring.util.http-response :refer [ok conflict]]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.onr :as onr]
            [yki.boundary.registration-db :as registration-db]
            [yki.spec :refer [ssn->date]]
            [yki.util.common :as common]
            [yki.util.exam-payment-helper :refer [get-payment-amount-for-registration]]
            [yki.util.template-util :as template-util])
  (:import (org.postgresql.util PSQLException)))

(defn sha256-hash [code]
  (-> code
      (hash/sha256)
      (bytes->hex)))

(defn get-participant-id
  [db identity]
  (:id (registration-db/get-participant-by-external-id db (:external-user-id identity))))

(defn get-or-create-participant
  [db identity]
  (:id (registration-db/get-or-create-participant! db {:external_user_id (:external-user-id identity)
                                                       :email            nil})))

(defn- sanitized-form [form]
  (let [text-fields (dissoc form :nationalities)
        sanitizer   (partial common/sanitized-string "_")
        sanitized   (update-vals text-fields sanitizer)]
    (merge form sanitized)))

(defn- create-init-response
  [db session exam-session-id registration-id payment-config]
  (let [exam-session            (exam-session-db/get-exam-session-by-id db exam-session-id)
        authenticated-by-email? (= (:auth-method session) "EMAIL")
        email                   (when authenticated-by-email? (:external-user-id (:identity session)))
        user                    (assoc (:identity session) :email email)
        exam-fee                (get-in payment-config [:amount (keyword (:level_code exam-session))])]
    {:exam_session           (assoc exam-session :exam_fee exam-fee)
     :is_strongly_identified (not authenticated-by-email?)
     :registration_id        registration-id
     :user                   user}))

(defn- init-error-response [space-left? not-registered? exam-session-id]
  (let [error {:error {:full       (not space-left?)
                       :registered (not not-registered?)}}]
    (log/warn "END: Init exam session" exam-session-id "failed with error" error)
    (conflict error)))

(defn- max-participants-error? [^Exception e]
  (and
    (instance? PSQLException e)
    (some->
      (.getServerErrorMessage ^PSQLException e)
      (.getMessage)
      (str/starts-with?
        "max_participants of exam_session exceeded"))))

(defn- create-registration [db exam-session-id participant-id session payment-config]
  (try
    (let [registration-id (registration-db/create-registration! db {:exam_session_id exam-session-id
                                                                    :participant_id  participant-id
                                                                    :started_at      (t/now)})
          response        (create-init-response db session exam-session-id registration-id payment-config)]
      (log/info "END: Init exam session" exam-session-id "registration success" registration-id)
      (ok response))
    (catch Exception e
      (if (max-participants-error? e)
        (conflict {:error {:full true}})
        (do
          (log/error e "Caught unexpected error within create-registration")
          (conflict {:error {:full       false
                             :registered false}}))))))

(defn init-registration
  [db session {:keys [exam_session_id]} payment-config]
  (log/info "START: Init exam session" exam_session_id "registration")
  (let [participant-id          (get-or-create-participant db (:identity session))
        started-registration-id (registration-db/get-started-registration-id-by-participant-id db participant-id exam_session_id)]
    (log/warn "started-registration-id" started-registration-id)
    (if started-registration-id
      (ok (create-init-response db session exam_session_id started-registration-id payment-config))
      (cond
        ; admission open
        (registration-db/exam-session-registration-open? db exam_session_id)
        (let [space-left?     (registration-db/exam-session-space-left? db exam_session_id nil)
              not-registered? (registration-db/not-registered-to-exam-session? db participant-id exam_session_id)]
          (if (and space-left? not-registered?)
            (create-registration db exam_session_id participant-id session payment-config)
            (init-error-response space-left? not-registered? exam_session_id)))

        ;post-admission open
        (registration-db/exam-session-post-registration-open? db exam_session_id)
        (let [quota-left?     (registration-db/exam-session-quota-left? db exam_session_id nil)
              not-registered? (registration-db/not-registered-to-exam-session? db participant-id exam_session_id)]
          (if (and quota-left? not-registered?)
            (create-registration db exam_session_id participant-id session payment-config)
            (init-error-response quota-left? not-registered? exam_session_id)))

        ; no registration open
        :else
        (conflict {:error {:closed true}})))))

(defn create-and-send-link [db url-helper email-q lang payment-link template-data]
  (let [code      (str (random-uuid))
        login-url (url-helper :yki.login-link.url code)
        email     (:email (registration-db/get-participant-by-id db (:participant_id payment-link)))
        link-type (:type payment-link)
        hashed    (sha256-hash code)]
    (login-link-db/create-login-link! db (assoc payment-link :code hashed))
    (log/info "Payment link created for " email ". Adding to email queue")
    (pgq/put email-q
             {:recipients [email]
              :created    (System/currentTimeMillis)
              :subject    (template-util/subject link-type lang template-data)
              :body       (template-util/render link-type lang (assoc template-data :login_url login-url))})))

;; Get registration data with participant found in session
;; In a case user has two different registration forms open and a non matching session,
;; checks for a matching open registration for the current one
(defn get-registration-data [db registration-id participant-id lang]
  (if-let [with-participant (registration-db/get-registration-data db registration-id participant-id lang)]
    with-participant
    (registration-db/get-registration-data-by-participant db registration-id participant-id lang)))

(defn get-open-registrations-by-participant [db user]
  {:open_registrations
   (registration-db/get-open-registrations-by-participant
     db
     (get-in user [:identity :external-user-id]))})

(defn- registration->expiration-date [registration]
  (let [post-admission?                 (= (:kind registration) "POST_ADMISSION")
        registration-end-date           (common/next-start-of-day
                                          (f/parse
                                            (if post-admission?
                                              (:post_admission_end_date registration)
                                              (:registration_end_date registration))))
        ongoing-registration-expiration (if post-admission?
                                          (common/date-from-now 1)
                                          (common/date-from-now 8))]
    (t/min-date
      ongoing-registration-expiration
      registration-end-date)))

(defn- with-session-details [form {:keys [auth-method identity]}]
  (if (= auth-method "EMAIL")
    (assoc form :email (:external-user-id identity))
    (assoc form :ssn (:ssn identity))))

(defn- with-birthdate [form]
  (if (:birthdate form)
    form
    (assoc form :birthdate (some-> form
                                   :ssn
                                   ssn->date
                                   common/format-date-for-db))))

(defn submit-registration-abstract-flow
  [db url-helper payment-helper email-q lang session registration-id raw-form onr-client exam-session-registration]
  (let [form                   (sanitized-form raw-form)
        identity               (:identity session)
        form-to-persist        (-> form
                                   (with-session-details session)
                                   (with-birthdate))
        session-participant-id (get-participant-id db identity)
        email                  (:email form)
        started?               (= (:state exam-session-registration) "STARTED")]
    (log/info (str "Get registration data with registration id " registration-id ", participant id " session-participant-id " and lang " lang ". Current state: " (:state exam-session-registration)))
    (when email
      (registration-db/update-participant-email! db email session-participant-id))
    (if-let [registration-data (when started? (get-registration-data db registration-id session-participant-id lang))]
      (if-let [oid (or (:oid identity)
                       (onr/get-or-create-person
                         onr-client
                         (assoc form-to-persist :registration_id registration-id)))]
        (let [amount                   (get-payment-amount-for-registration payment-helper exam-session-registration)
              ; Use the same participant id for registration and the payment link as otherwise the payment link won't work.
              unified-participant-id   (or (:participant_id registration-data) session-participant-id)
              update-registration      {:id             registration-id
                                        :form           form-to-persist
                                        :oid            oid
                                        :form_version   1
                                        :participant_id unified-participant-id}
              expiration-date          (registration->expiration-date registration-data)
              payment-success-url      (url-helper :exam-payment-v3.redirect registration-id lang)
              payment-link-expired-url (url-helper :yki-ui.registration.payment-link-expired.url)
              payment-link             {:participant_id        unified-participant-id
                                        :exam_session_id       nil
                                        :registration_id       registration-id
                                        :expires_at            expiration-date
                                        :success_redirect      payment-success-url
                                        :expired_link_redirect payment-link-expired-url
                                        :type                  "PAYMENT"}
              create-and-send-link-fn  #(create-and-send-link db
                                                              url-helper
                                                              email-q
                                                              lang
                                                              payment-link
                                                              (assoc registration-data
                                                                :amount (:email-template amount)
                                                                :language (template-util/get-language (:language_code registration-data) lang)
                                                                :level (template-util/get-level (:level_code registration-data) lang)
                                                                :expiration_date (common/format-date-to-finnish-format expiration-date)))
              success                  (registration-db/update-registration-details! db
                                                                                     update-registration
                                                                                     create-and-send-link-fn)]
          (if success
            (do
              (log/info "END: Registration id" registration-id "submitted successfully")
              (try
                (exam-session-db/remove-from-exam-session-queue! db email (:id exam-session-registration))
                (catch Exception e
                  (log/error e "Failed to remove email" email "from exam session" (:id exam-session-registration) "queue")))
              {:oid oid})
            {:error {:create_payment true}}))
        {:error {:person_creation true}})
      ; Submitting form didn't succeed due to some other reason.
      ; Likely something akin to a race condition: the registration may have expired by the time we got here
      ; or the registration period may have ended.
      ; Check the most likely cases against the current database state and return error response.
      (let [{state                :state
             open?                :open
             post-admission-open? :post_admission_open} (registration-db/get-registration-and-exam-session-state db (:id exam-session-registration))]
        {:error {:expired (= "EXPIRED" state)
                 :state   state
                 :closed  (not (or open? post-admission-open?))}}))))

(defn submit-registration
  [db url-helper payment-helper email-q lang session registration-id form onr-client]
  (log/info "START: Submitting registration id" registration-id)
  (let [exam-session-registration (exam-session-db/get-exam-session-registration-by-registration-id db registration-id)]
    (if
      (or
        (registration-db/exam-session-space-left? db (:id exam-session-registration) registration-id)
        (registration-db/exam-session-quota-left? db (:id exam-session-registration) registration-id))
      (submit-registration-abstract-flow db url-helper payment-helper email-q lang session registration-id form onr-client exam-session-registration)
      ; registration is already full, cannot add new
      {:error {:full true}})))
