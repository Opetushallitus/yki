(ns yki.registration.registration
  (:require [buddy.core.codecs :refer [bytes->hex]]
            [buddy.core.hash :as hash]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [pgqueue.core :as pgq]
            [ring.util.http-response :refer [bad-request ok conflict]]
            [yki.boundary.codes :as codes]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.onr :as onr]
            [yki.boundary.person-db :as person-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.yki-register :as yki-register]
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

(defn get-participant-id-by-session
  [db session]
  (:id (registration-db/get-participant-by-external-id db (or (get-in session [:identity :previous-session-id])
                                                              (:yki-session-id session)))))

(defn get-or-create-session
  [session]
  (if (get-in session [:identity :external-user-id])
    session
    (let [session-id (str (random-uuid))]
      {:identity       {:external-user-id session-id}
       :auth-method    "SESSION"
       :yki-session-id session-id})))

(defn get-or-create-participant
  [db identity]
  (:id (registration-db/get-or-create-participant! db {:external_user_id (:external-user-id identity)
                                                       :email            nil})))

(defn update-registration-participant-id!
  [db registration-id participant-id]
  (registration-db/update-registration-participant-id! db registration-id participant-id))

(defn update-participant-external-id!
  [db participant-id session]
  (registration-db/update-participant-external-id! db {:external_user_id (:external-user-id (:identity session))
                                                       :id               participant-id}))

(defn- sanitized-form [form]
  (let [text-fields (dissoc form :nationalities)
        sanitizer   (partial common/sanitized-string "_")
        sanitized   (update-vals text-fields sanitizer)]
    (merge form sanitized)))

(defn- create-registration-response
  [db session exam-session-id registration-id registration-kind payment-config]
  (let [exam-session              (exam-session-db/get-exam-session-by-id db exam-session-id)
        authenticated-by-email?   (= (:auth-method session) "EMAIL")
        authenticated-by-session? (= (:auth-method session) "SESSION")
        email                     (when authenticated-by-email? (:external-user-id (:identity session)))
        user                      (assoc (:identity session) :email email)
        exam-fee                  (get-in payment-config [:amount (keyword (:level_code exam-session))])]
    (assoc
      (ok {:exam_session           (assoc exam-session :exam_fee exam-fee)
           :is_strongly_identified (and (not authenticated-by-email?) (not authenticated-by-session?))
           :registration_id        registration-id
           :registration_kind      registration-kind
           :user                   user})
      :session session)))

(defn- init-error-response [space-left? other-registration to-queue? exam-session-id]
  (let [error {:error {:full                            (not space-left?)
                       :other-exam-session-registration other-registration
                       :to-queue                        to-queue?}}]
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
          response        (create-registration-response db session exam-session-id registration-id registration-kind payment-config)]
      (log/info "END: Init exam session" exam-session-id "registration success" registration-id)
      response)
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
  (let [session-new          (get-or-create-session session)
        ;participant-id          (get-or-create-participant db {:external-user-id "teppo.teikalainen@test.invalid"})
        participant-id       (get-or-create-participant db (:identity session-new))
        started-registration (registration-db/get-started-registration-id+kind-by-participant-id db participant-id exam_session_id)]
    (log/info "started-registration-id" (:id started-registration))
    (if started-registration
      (create-registration-response db session-new exam_session_id (:id started-registration) (:kind started-registration) payment-config)
      (if (registration-db/exam-session-registration-open? db exam_session_id)
        ; admission open
        (let [space-left?        (registration-db/exam-session-space-left? db exam_session_id nil)
              other-registration (registration-db/participant-registered-to-exam-on-exam-date? db participant-id exam_session_id)
              registration-kind  (if to_queue "QUEUE" "ADMISSION")]
          (if (and (not other-registration)
                   (or to_queue space-left?))
            (create-registration db exam_session_id participant-id registration-kind session-new payment-config)
            (init-error-response space-left? other-registration to_queue exam_session_id)))
        ; no registration open
        (conflict {:error {:closed true}})))))

(defn identify-registration
  [db session {:keys [exam_session_id to_queue]} payment-config]
  (log/info "START: identify exam session" exam_session_id "registration")
  (let [participant-id-session        (get-participant-id-by-session db session)
        participant-id-other          (get-participant-id db (:identity session))
        found-session-registration    (and participant-id-session (registration-db/get-started-registration-id+kind-by-participant-id db participant-id-session exam_session_id))
        found-other-registration      (and participant-id-other (registration-db/get-started-registration-id+kind-by-participant-id db participant-id-other exam_session_id))
        registration-to-other-session (and participant-id-other (registration-db/participant-registered-to-other-exam-on-exam-date? db participant-id-other exam_session_id))]
    ; (log/info "found-registration-id" (:id found-registration))
    (cond
      (some? found-other-registration) (create-registration-response db session exam_session_id (:id found-other-registration) (:kind found-other-registration) payment-config)
      (some? found-session-registration) (if-not registration-to-other-session
                                           (do
                                             (if participant-id-other
                                               (update-registration-participant-id! db (:id found-session-registration) participant-id-other)
                                               (update-participant-external-id! db participant-id-session session))
                                             (create-registration-response db session exam_session_id (:id found-session-registration) (:kind found-session-registration) payment-config))
                                           (init-error-response true registration-to-other-session (if to_queue "QUEUE" "ADMISSION") exam_session_id))
      :else (bad-request {:reason :registration-not-found}))))

(defn send-payment-link-email! [email-q lang recipient template-name template-data]
  (pgq/put
    email-q
    {:recipients [recipient]
     :created    (System/currentTimeMillis)
     :subject    (template-util/subject template-name lang template-data)
     :body       (template-util/render template-name lang template-data)}))

(defn create-and-send-payment-link [db email-q lang payment-link template-name template-data code login-url]
  (let [email  (:email template-data)
        hashed (sha256-hash code)]
    (login-link-db/create-login-link! db (assoc payment-link :code hashed :user_data nil))
    (log/info "Payment link created for " email ". Adding to email queue")
    (send-payment-link-email! email-q lang email template-name (assoc template-data :login_url login-url))))

(defn create-user-portal-link [db url-helper participant-id registration-id exam-date]
  (let [code            (str (random-uuid))
        login-url       (url-helper :yki.login-link.url code)
        hashed          (sha256-hash code)
        success-url     (url-helper :yki-ui.user-portal.url)
        expired-url     (url-helper :yki-ui.user-portal.expired-link)
        expiration-date (common/date-from-now (inc 14))
        link-data       {:participant_id        participant-id
                         :exam_session_id       nil
                         :registration_id       registration-id
                         :expires_at            expiration-date
                         :success_redirect      success-url
                         :expired_link_redirect expired-url
                         :type                  "PERSON"
                         :code                  hashed
                         :user_data             nil}]
    (login-link-db/create-login-link! db link-data)
    login-url))

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

(defn- registration->expiration-date [registration from-queue?]
  (if from-queue?
    (let [expires-at (:expires_at registration)]
      ; Lifted from queue: payment period is current day + one full day
      ; Expiration date in DB is updated when registration is lifted from queue.
      {:expiration-date   expires-at
       :last-payment-date (-> expires-at
                              (common/format-date-for-db)
                              (f/parse-local-date))})
    (let [date-str                        (:registration_end_date registration)
          registration-end-date           (-> (f/parse-local-date date-str)
                                              (common/next-start-of-day))
          ; New spec for regular admission:
          ; - payment due in three whole days OR until end of registration period
          ; - IF registration ends in less than two days' time, grant payment period of current day + one full day
          ongoing-registration-expiration (common/date-from-now (inc 3))
          date-of-expiry                  (t/min-date
                                            ongoing-registration-expiration
                                            registration-end-date)]
      {:expiration-date   date-of-expiry
       ; We want to indicate the last possible payment date in email templates.
       ; The last payment date will be the day before expiration date.
       :last-payment-date (common/previous-day date-of-expiry)})))

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

(defn- with-gender-and-nationality [{:keys [gender ssn nationalities] :as form}]
  (let [gender      (yki-register/convert-gender gender ssn)
        nationality (first nationalities)]
    (assoc form :gender gender :nationality_code nationality)))

(defn- ->send-registration-email! [db url-helper payment-helper email-q lang registration-data code login-url email-auth?]
  (case (:kind registration-data)
    "ADMISSION"
    (let [registration-id          (:id registration-data)
          participant-id           (:participant_id registration-data)
          amount                   (get-payment-amount-for-registration payment-helper registration-data)
          {:keys [expiration-date last-payment-date]} (registration->expiration-date registration-data false)
          payment-success-url      (url-helper :exam-payment-v3.redirect registration-id lang)
          payment-link-expired-url (url-helper :yki-ui.registration.payment-link-expired.url)
          payment-link             {:participant_id        participant-id
                                    :exam_session_id       nil
                                    :registration_id       registration-id
                                    :expires_at            expiration-date
                                    :success_redirect      payment-success-url
                                    :expired_link_redirect payment-link-expired-url
                                    :type                  "PAYMENT"}
          user-portal-link         (when email-auth? (create-user-portal-link db url-helper participant-id registration-id (:exam_date registration-data)))]
      #(create-and-send-payment-link db
                                     email-q
                                     lang
                                     payment-link
                                     "PAYMENT"
                                     (assoc registration-data
                                       :amount (:email-template amount)
                                       :language (template-util/get-language (:language_code registration-data) lang)
                                       :level (template-util/get-level (:level_code registration-data) lang)
                                       :expiration_date (common/format-date-to-finnish-format last-payment-date)
                                       :user_portal_link (or user-portal-link (url-helper :yki.login.user-portal)))
                                     code
                                     login-url))
    "QUEUE"
    (let [participant-id   (:participant_id registration-data)
          user-portal-link (if email-auth?
                             (create-user-portal-link db url-helper participant-id (:id registration-data) (:exam_date registration-data))
                             (url-helper :yki.login.user-portal))]

      #(send-enrolled-to-queue-email! email-q lang (assoc registration-data :user_portal_link user-portal-link)))))

(defn send-lifted-from-queue-email! [db url-helper payment-helper email-q lang registration-data code login-url]
  (let [registration-id          (:id registration-data)
        participant-id           (:participant_id registration-data)
        amount                   (get-payment-amount-for-registration payment-helper registration-data)
        {:keys [expiration-date last-payment-date]} (registration->expiration-date registration-data true)
        payment-success-url      (url-helper :exam-payment-v3.redirect registration-id lang)
        payment-link-expired-url (url-helper :yki-ui.registration.payment-link-expired.url)
        payment-link             {:participant_id        participant-id
                                  :exam_session_id       nil
                                  :registration_id       registration-id
                                  :expires_at            expiration-date
                                  :success_redirect      payment-success-url
                                  :expired_link_redirect payment-link-expired-url
                                  :type                  "PAYMENT"}
        user-portal-link         (when (:is_email_auth registration-data)
                                   (create-user-portal-link db url-helper participant-id registration-id (:exam_date registration-data)))]
    (create-and-send-payment-link db
                                  email-q
                                  lang
                                  payment-link
                                  "PAYMENT_FROM_QUEUE"
                                  (assoc registration-data
                                    :amount (:email-template amount)
                                    :language (template-util/get-language (:language_code registration-data) lang)
                                    :level (template-util/get-level (:level_code registration-data) lang)
                                    :expiration_date (common/format-date-to-finnish-format last-payment-date)
                                    :user_portal_link (or user-portal-link (url-helper :yki.login.user-portal)))
                                  code
                                  login-url)))

(defn submit-registration-abstract-flow
  [db url-helper payment-helper email-q lang session registration-id raw-form onr-client exam-session-registration]
  (let [form                   (sanitized-form raw-form)
        identity               (:identity session)
        email-auth?            (= (:auth-method session) "EMAIL")
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
              ; For queued registrations, expiration date is not very meaningful as of yet.
              ; If the registration is ultimately lifted from queue, the expiration date will be recalculated.
              {:keys [expiration-date]} (registration->expiration-date registration-data false)
              update-registration     {:id             registration-id
                                       :form           form-to-persist
                                       :oid            oid
                                       :form_version   1
                                       :participant_id unified-participant-id
                                       :expires_at     expiration-date
                                       :exam_fee       (:db amount)
                                       :ui_language    lang}
              code                    (str (random-uuid))
              login-url               (url-helper :yki.login-link.url code)
              email-template-data     (assoc registration-data
                                        :email
                                        (or email
                                            (:email (registration-db/get-participant-by-id db unified-participant-id)))
                                        :participant_id unified-participant-id)
              create-and-send-link-fn (->send-registration-email! db url-helper payment-helper email-q lang email-template-data code login-url email-auth?)
              update-person           (-> form
                                          (with-gender-and-nationality)
                                          (assoc :oid oid))
              person                  (person-db/upsert-person! db update-person)
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
            (let [already-registered? (registration-db/is-person-already-registered-on-exam-date? db oid registration-id)]
              {:error {:create_payment true
                       :registered     already-registered?}})))
        {:error {:person_creation true}})
      ; Submitting form didn't succeed due to some other reason.
      ; Likely something akin to a race condition: the registration may have expired by the time we got here
      ; or the registration period may have ended.
      ; Check the most likely cases against the current database state and return error response.
      (let [{state :state
             open? :open} (registration-db/get-registration-and-exam-session-state db registration-id)]
        {:error {:expired (= "EXPIRED" state)
                 :state   state
                 :closed  (not open?)}}))))

(defn submit-registration
  [db url-helper payment-helper email-q lang session registration-id form onr-client]
  (log/info "START: Submitting registration id" registration-id)
  (let [exam-session-registration (exam-session-db/get-exam-session-registration-by-registration-id db registration-id)]
    (submit-registration-abstract-flow db url-helper payment-helper email-q lang session registration-id form onr-client exam-session-registration)))
