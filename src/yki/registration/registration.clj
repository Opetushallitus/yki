(ns yki.registration.registration
  (:require [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.string :as str]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.onr :as onr]
            [yki.util.common :as c]
            [ring.util.http-response :refer [ok conflict]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer [bytes->hex]]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defn get-participant-id
  [db identity]
  (:id (registration-db/get-participant-by-external-id db (:external-user-id identity))))

(defn get-or-create-participant
  [db identity]
  (:id (registration-db/get-or-create-participant! db {:external_user_id (:external-user-id identity)
                                                       :email nil})))

(defn- extract-nationalities
  [nationalities]
  (map (fn [n] {:kansalaisuusKoodi n}) nationalities))

(defn- extract-person-from-registration
  [{:keys [email first_name last_name gender exam_lang nationalities birthdate]} ssn]
  (let [basic-fields {:yhteystieto    [{:yhteystietoTyyppi "YHTEYSTIETO_SAHKOPOSTI"
                                        :yhteystietoArvo   email}]
                      :etunimet       first_name
                      :kutsumanimi    (first (str/split first_name #" "))
                      :sukunimi       last_name
                      :sukupuoli      (if (str/blank? gender) nil gender)
                      :asiointiKieli  {:kieliKoodi exam_lang}
                      :kansalaisuus   (extract-nationalities nationalities)
                      :henkiloTyyppi  "OPPIJA"}]
    (if ssn
      (assoc
       basic-fields
       :hetu (clojure.string/upper-case ssn)
       :eiSuomalaistaHetua false)
      (assoc
       basic-fields
       :syntymaaika birthdate
       :identifications [{:idpEntityId "oppijaToken" :identifier email}]
       :eiSuomalaistaHetua true))))

(defn- create-init-response
  [db session exam_session_id registration-id payment-config]
  (let [exam-session (exam-session-db/get-exam-session-by-id db exam_session_id)
        email        (when (= (:auth-method session) "EMAIL") (:external-user-id (:identity session)))
        user         (assoc (:identity session) :email email)
        exam_fee     (get-in payment-config [:amount (keyword (:level_code exam-session))])]
    {:exam_session (assoc exam-session :exam_fee exam_fee)
     :user user
     :registration_id registration-id}))

(defn init-registration
  [db session {:keys [exam_session_id]} payment-config]
  (log/info "START: Init exam session" exam_session_id "registration")
  (let [participant-id (get-or-create-participant db (:identity session))
        started-registration-id (registration-db/started-registration-id-by-participant db participant-id exam_session_id)]
    (log/warn "started-registration-id" started-registration-id)
    (if started-registration-id
      (ok (create-init-response db session exam_session_id started-registration-id payment-config))
      (cond
        (registration-db/exam-session-registration-open? db exam_session_id)
        (let [space-left?        (registration-db/exam-session-space-left? db exam_session_id nil)
              not-registered?    (registration-db/not-registered-to-exam-session? db participant-id exam_session_id)]
          (if (and space-left? not-registered?)
            ; TODO figure out if ADMISSION or POST_ADMISSION
            (let [registration-id (registration-db/create-registration! db {:exam_session_id exam_session_id
                                                                            :participant_id  participant-id
                                                                            :started_at      (t/now)})
                  response        (create-init-response db session exam_session_id registration-id payment-config)]
              (log/info "END: Init exam session" exam_session_id "registration success" registration-id)
              (ok response))
            (let [error {:error {:full       (not space-left?)
                                 :registered (not not-registered?)}}]
              (log/warn "END: Init exam session" exam_session_id "failed with error" error)
              (conflict error))))

        :else  ; no registration open
        (conflict {:error {:closed true}})))))

(defn create-and-send-link [db url-helper email-q lang login-link template-data]
  (let [code          (str (UUID/randomUUID))
        login-url     (url-helper :yki.login-link.url code)
        email         (:email (registration-db/get-participant-by-id db (:participant_id login-link)))
        link-type     (:type login-link)
        hashed        (sha256-hash code)]
    (login-link-db/create-login-link! db
                                      (assoc login-link
                                             :code hashed))
    (pgq/put email-q
             {:recipients [email]
              :created (System/currentTimeMillis)
              :subject (template-util/subject url-helper link-type lang template-data)
              :body (template-util/render url-helper link-type lang (assoc template-data :login-url login-url))})))

(defn submit-registration-abstract-flow
  []
  (fn [db url-helper email-q lang session registration-id form payment-config onr-client exam-session-registration]
    (let [identity        (:identity session)
          form-with-email (if (= (:auth-method session) "EMAIL")
                            (assoc form :email (:external-user-id identity))
                            (assoc form :ssn (:ssn identity)))
          participant-id  (get-participant-id db identity)
          email           (:email form)
          started?        (= (:state exam-session-registration) "STARTED")]  ; TODO: Mikä tila on kun ilmo on ohi, mutta jälki-ilmo auki?
      (when email
        (registration-db/update-participant-email! db email participant-id))
      (if-let [registration-data (when started? (registration-db/get-registration-data db registration-id participant-id lang))]
        (if-let [oid                 (or (:oid identity)
                                         (onr/get-or-create-person
                                          onr-client
                                          (extract-person-from-registration
                                           form-with-email
                                           (:ssn identity))))]
          (let [amount                  (bigdec (get-in payment-config [:amount (keyword (:level_code exam-session-registration))]))
                payment                 {:registration_id registration-id
                                         :lang            lang
                                         :amount          amount}
                update-registration     {:id             registration-id
                                         :form           form-with-email
                                         :oid            oid
                                         :form_version   1
                                         :participant_id participant-id}
                registration-end-time   (c/next-start-of-day (f/parse (:registration_end_date registration-data)))
                expiration-date         (t/min-date (c/date-from-now 8) registration-end-time)
                payment-link            {:participant_id        participant-id
                                         :exam_session_id       nil
                                         :registration_id       registration-id
                                         :expires_at            expiration-date
                                         :expired_link_redirect (url-helper :payment-link-expired.redirect lang)
                                         :success_redirect      (url-helper :payment-link.redirect registration-id lang)
                                         :type                  "PAYMENT"}
                create-and-send-link-fn #(create-and-send-link db
                                                               url-helper
                                                               email-q
                                                               lang
                                                               payment-link
                                                               (assoc registration-data
                                                                      :amount amount
                                                                      :language (template-util/get-language url-helper (:language_code registration-data) lang)
                                                                      :level (template-util/get-level url-helper (:level_code registration-data) lang)
                                                                      :expiration-date (c/format-date-to-finnish-format expiration-date)))
                success                 (registration-db/create-payment-and-update-registration! db
                                                                                                 payment
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
        {:error (if started? {:closed true} {:expired true})}))))

(defn submit-registration
  [db url-helper email-q lang session registration-id form payment-config onr-client]
  (log/info "START: Submitting registration id" registration-id)
  (let [exam-session-registration (exam-session-db/get-exam-session-registration-by-registration-id db registration-id)]
    (cond
      (registration-db/exam-session-space-left? db (:id exam-session-registration) registration-id)
      ((submit-registration-abstract-flow) db url-helper email-q lang session registration-id form payment-config onr-client exam-session-registration)

      ;(registration-db/exam-session-post-space-left? db (:id exam-session) registration-id)
      ;((submit-registration-abstract-flow) db url-helper email-q lang session registration-id form payment-config onr-client exam-session-registration)

      :else  ; registration is already full, cannot add new
      {:error {:full true}})))
