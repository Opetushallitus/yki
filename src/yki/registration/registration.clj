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
            [yki.boundary.person-db :as person-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.registration.email :refer [send-enrolled-to-queue-email!]]
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
  [db session exam-session-id registration-id registration-kind payment-config]
  (let [exam-session            (exam-session-db/get-exam-session-by-id db exam-session-id)
        authenticated-by-email? (= (:auth-method session) "EMAIL")
        email                   (when authenticated-by-email? (:external-user-id (:identity session)))
        user                    (assoc (:identity session) :email email)
        exam-fee                (get-in payment-config [:amount (keyword (:level_code exam-session))])]
    {:exam_session           (assoc exam-session :exam_fee exam-fee)
     :is_strongly_identified (not authenticated-by-email?)
     :registration_id        registration-id
     :registration_kind      registration-kind
     :user                   user}))

(defn- init-error-response [space-left? not-registered? to-queue? exam-session-id]
  (let [error {:error {:full       (not space-left?)
                       :registered (not not-registered?)
                       :to-queue   to-queue?}}]
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

(defn- registration-kind-mismatch? [^Exception e]
  (and
    (instance? PSQLException e)
    (some->
      (.getServerErrorMessage ^PSQLException e)
      (.getMessage)
      (str/starts-with?
        "registration to queue is not available"))))

(defn- create-registration [db exam-session-id participant-id registration-kind session payment-config]
  (try
    (let [registration-id (registration-db/create-registration! db {:exam_session_id exam-session-id
                                                                    :participant_id  participant-id
                                                                    :started_at      (t/now)
                                                                    :kind            registration-kind})
          response        (create-init-response db session exam-session-id registration-id registration-kind payment-config)]
      (log/info "END: Init exam session" exam-session-id "registration success" registration-id)
      (ok response))
    (catch Exception e
      (cond
        (max-participants-error? e)
        (conflict {:error {:full true}})
        (registration-kind-mismatch? e)
        (conflict {:error {:registration_kind true}})
        :else
        (do
          (log/error e "Caught unexpected error within create-registration")
          (conflict {:error {:full       false
                             :registered false}}))))))

(defn init-registration
  [db session {:keys [exam_session_id to_queue]} payment-config]
  (log/info "START: Init exam session" exam_session_id "registration")
  (let [
        ;participant-id          (get-or-create-participant db {:external-user-id "teppo.teikalainen@test.invalid"})
        participant-id       (get-or-create-participant db (:identity session))
        started-registration (registration-db/get-started-registration-id+kind-by-participant-id db participant-id exam_session_id)]
    (log/info "started-registration-id" (:id started-registration))
    (if started-registration
      (ok (create-init-response db session exam_session_id (:id started-registration) (:kind started-registration) payment-config))
      (if (registration-db/exam-session-registration-open? db exam_session_id)
        ; admission open
        (let [space-left?       (registration-db/exam-session-space-left? db exam_session_id nil)
              not-registered?   (registration-db/not-registered-to-exam-session? db participant-id exam_session_id)
              registration-kind (if to_queue "QUEUE" "ADMISSION")]
          (if (and not-registered?
                   (or to_queue space-left?))
            (create-registration db exam_session_id participant-id registration-kind session payment-config)
            (init-error-response space-left? not-registered? to_queue exam_session_id)))
        ; no registration open
        (conflict {:error {:closed true}})))))

(defn send-payment-link-email! [email-q lang recipient template-name template-data]
  (pgq/put
    email-q
    {:recipients [recipient]
     :created    (System/currentTimeMillis)
     :subject    (template-util/subject template-name lang template-data)
     :body       (template-util/render template-name lang template-data)}))

(defn create-and-send-payment-link [db email-q lang payment-link template-name template-data code login-url]
  ; TODO Should either consider reading email preferentially from person table or ensure that email within participant table gets
  ;  updated whenever email for corresponding person gets updated..
  (let [email  (:email (registration-db/get-participant-by-id db (:participant_id payment-link)))
        hashed (sha256-hash code)]
    (login-link-db/create-login-link! db (assoc payment-link :code hashed))
    (log/info "Payment link created for " email ". Adding to email queue")
    (send-payment-link-email! email-q lang email template-name (assoc template-data :login_url login-url))))

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
  (let [date-str                        (:registration_end_date registration)
        registration-end-date           (-> (f/parse-local-date date-str)
                                            (common/next-start-of-day))
        ; Registration and payment link expiry should be three whole days from today
        ; => expiry at start of day 3+1 days from now.
        ; TODO Separate expiration date calculation logic registration lifted from queue
        ;  Can't be tied to registration end date, as the queueing period is supposed to last roughly a week longer?
        ongoing-registration-expiration (common/date-from-now (inc 3))
        date-of-expiry                  (t/min-date
                                          ongoing-registration-expiration
                                          registration-end-date)]
    {:expiration-date   date-of-expiry
     ; We want to indicate the last possible payment date in email templates.
     ; The last payment date will be the day before expiration date.
     :last-payment-date (common/previous-day date-of-expiry)}))

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

(defn- ->send-registration-email! [db url-helper payment-helper email-q lang registration-data code login-url]
  (case (:kind registration-data)
    "ADMISSION"
    (let [registration-id          (:id registration-data)
          participant-id           (:participant_id registration-data)
          amount                   (get-payment-amount-for-registration payment-helper registration-data)
          {:keys [expiration-date last-payment-date]} (registration->expiration-date registration-data)
          payment-success-url      (url-helper :exam-payment-v3.redirect registration-id lang)
          payment-link-expired-url (url-helper :yki-ui.registration.payment-link-expired.url)
          payment-link             {:participant_id        participant-id
                                    :exam_session_id       nil
                                    :registration_id       registration-id
                                    :expires_at            expiration-date
                                    :success_redirect      payment-success-url
                                    :expired_link_redirect payment-link-expired-url
                                    :type                  "PAYMENT"}]
      #(create-and-send-payment-link db
                                     email-q
                                     lang
                                     payment-link
                                     "PAYMENT"
                                     (assoc registration-data
                                       :amount (:email-template amount)
                                       :language (template-util/get-language (:language_code registration-data) lang)
                                       :level (template-util/get-level (:level_code registration-data) lang)
                                       :expiration_date (common/format-date-to-finnish-format last-payment-date))
                                     code
                                     login-url))
    "QUEUE"
    (let [participant-id (:participant_id registration-data)
          email          (:email (registration-db/get-participant-by-id db participant-id))]
      #(send-enrolled-to-queue-email! email-q lang (assoc registration-data :email email)))))

(defn send-lifted-from-queue-email! [db url-helper payment-helper email-q lang registration-data code login-url]
  (let [registration-id          (:id registration-data)
        participant-id           (:participant_id registration-data)
        amount                   (get-payment-amount-for-registration payment-helper registration-data)
        {:keys [expiration-date last-payment-date]} (registration->expiration-date registration-data)
        payment-success-url      (url-helper :exam-payment-v3.redirect registration-id lang)
        payment-link-expired-url (url-helper :yki-ui.registration.payment-link-expired.url)
        payment-link             {:participant_id        participant-id
                                  :exam_session_id       nil
                                  :registration_id       registration-id
                                  :expires_at            expiration-date
                                  :success_redirect      payment-success-url
                                  :expired_link_redirect payment-link-expired-url
                                  :type                  "PAYMENT"}]
    (create-and-send-payment-link db
                                  email-q
                                  lang
                                  payment-link
                                  "PAYMENT_FROM_QUEUE"
                                  (assoc registration-data
                                    :amount (:email-template amount)
                                    :language (template-util/get-language (:language_code registration-data) lang)
                                    :level (template-util/get-level (:level_code registration-data) lang)
                                    :expiration_date (common/format-date-to-finnish-format last-payment-date))
                                  code
                                  login-url)))

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
        (let [amount                  (get-payment-amount-for-registration payment-helper exam-session-registration)
              ; Use the same participant id for registration and the payment link as otherwise the payment link won't work.
              unified-participant-id  (or (:participant_id registration-data) session-participant-id)
              {:keys [expiration-date]} (registration->expiration-date registration-data)
              update-registration     {:id             registration-id
                                       :form           form-to-persist
                                       :oid            oid
                                       :form_version   1
                                       :participant_id unified-participant-id
                                       ; TODO Ensure expiration date is updated when registration is lifted from queue
                                       :expires_at     expiration-date
                                       :exam_fee       (:db amount)}
              code                    (str (random-uuid))
              login-url               (url-helper :yki.login-link.url code)
              create-and-send-link-fn (->send-registration-email! db url-helper payment-helper email-q lang (assoc registration-data :participant_id unified-participant-id) code login-url)
              person                  (person-db/upsert-person! db (assoc form :oid oid))
              success                 (and person
                                           (registration-db/update-registration-details!
                                             db
                                             update-registration
                                             create-and-send-link-fn))
              kind                    (:kind registration-data)
              response-base           {:oid               oid
                                       :registration_kind kind}]
          (if success
            (do
              (log/info "END: Registration id" registration-id "submitted successfully")
              (if (= kind "ADMISSION")
                (assoc response-base :code code)
                response-base))
            {:error {:create_payment true}}))
        {:error {:person_creation true}})
      ; Submitting form didn't succeed due to some other reason.
      ; Likely something akin to a race condition: the registration may have expired by the time we got here
      ; or the registration period may have ended.
      ; Check the most likely cases against the current database state and return error response.
      (let [{state :state
             open? :open} (registration-db/get-registration-and-exam-session-state db (:id exam-session-registration))]
        {:error {:expired (= "EXPIRED" state)
                 :state   state
                 :closed  (not open?)}}))))

(defn submit-registration
  [db url-helper payment-helper email-q lang session registration-id form onr-client]
  (log/info "START: Submitting registration id" registration-id)
  (let [exam-session-registration (exam-session-db/get-exam-session-registration-by-registration-id db registration-id)]
    (submit-registration-abstract-flow db url-helper payment-helper email-q lang session registration-id form onr-client exam-session-registration)))
