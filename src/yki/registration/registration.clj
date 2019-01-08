(ns yki.registration.registration
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.onr :as onr]
            [yki.util.common :as c]
            [ring.util.http-response :refer [ok conflict not-found internal-server-error]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [clojure.tools.logging :refer [info error]])
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
  (mapv (fn [[nat-code & _]] {:kansalaisuusKoodi nat-code}) nationalities))

(defn- extract-person-from-registration
  [{:keys [email first_name last_name gender exam_lang nationalities birth_date]} ssn]
  (let [basic-fields {:yhteystieto    [{:yhteystietoTyyppi "YHTEYSTIETO_SAHKOPOSTI"
                                        :yhteystietoArvo   email}]
                      :etunimet       first_name
                      :kutsumanimi    first_name
                      :sukunimi       last_name
                      :sukupuoli      gender
                      :aidinkieli     {:kieliKoodi exam_lang}
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
       :syntymaaika birth_date
       :identifications [{:idpEntityId "oppijaToken" :identifier email}]
       :eiSuomalaistaHetua true))))

(defn init-registration
  [db session {:keys [exam_session_id]}]
  (let [participant-id (get-or-create-participant db (:identity session))
        exam-session-registration-open? (registration-db/exam-session-registration-open? db exam_session_id)
        exam-session-space-left? (registration-db/exam-session-space-left? db exam_session_id)
        participant-not-registered? (registration-db/participant-not-registered? db participant-id exam_session_id)]
    (if (and exam-session-registration-open? exam-session-space-left? participant-not-registered?)
      (ok (registration-db/create-registration! db {:exam_session_id exam_session_id
                                                    :participant_id participant-id
                                                    :started_at (t/now)}))
      (conflict {:error {:full (not exam-session-space-left?)
                         :closed (not exam-session-registration-open?)
                         :registered (not participant-not-registered?)}}))))

(defn create-and-send-link [db url-helper email-q lang login-link template-data]
  (let [code          (str (UUID/randomUUID))
        login-url     (str (url-helper :yki.login-link.url) "?code=" code)
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

(defn submit-registration
  [db url-helper email-q lang session id form amount onr-client]
  (let [identity        (:identity session)
        form-with-email (if (= (:auth-method session) "EMAIL") (assoc form :email (:external-user-id identity)) form)
        participant-id  (get-participant-id db identity)
        email           (:email form)]
    (when email
      (registration-db/update-participant-email! db email participant-id))
    (if-let [registration-data (registration-db/get-registration-data db id participant-id lang)]
      (if-let [oid                 (or (:oid identity)
                                       (onr/get-or-create-person
                                        onr-client
                                        (extract-person-from-registration
                                         form-with-email
                                         (:ssn identity))))]
        (let [payment                   {:registration_id id
                                         :lang lang
                                         :amount amount}
              update-registration       {:id id
                                         :form form-with-email
                                         :oid oid
                                         :form_version 1
                                         :participant_id participant-id}
              registration-end-time     (c/next-start-of-day (f/parse (:registration_end_date registration-data)))
              expiration-date           (t/min-date (c/date-from-now 8) registration-end-time)
              payment-link              {:participant_id participant-id
                                         :exam_session_id nil
                                         :registration_id id
                                         :expires_at expiration-date
                                         :expired_link_redirect (url-helper :link-expired.redirect)
                                         :success_redirect (url-helper :payment-link.redirect id)
                                         :type "PAYMENT"}
              create-and-send-link-fn   #(create-and-send-link db
                                                               url-helper
                                                               email-q
                                                               lang
                                                               payment-link
                                                               (assoc registration-data :amount amount))
              success                   (registration-db/create-payment-and-update-registration! db
                                                                                                 payment
                                                                                                 update-registration
                                                                                                 create-and-send-link-fn)]
          (if success
            {:oid oid}
            {:error {:create_payment true}}))
        {:error {:person_creation true}})
      {:error {:closed true}})))

