(ns yki.registration.registration
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [pgqueue.core :as pgq]
            [clj-time.core :as t]
            [yki.util.template-util :as template-util]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.login-link-db :as login-link-db]
            [yki.boundary.onr :as onr]
            [ring.util.http-response :refer [ok conflict not-found internal-server-error]]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :refer :all]
            [clojure.tools.logging :refer [info error]])
  (:import [java.util UUID]))

(defn sha256-hash [code]
  (bytes->hex (hash/sha256 code)))

(defn- get-external-id-from-session [session]
  (let [identity (:identity session)]
    {:external_user_id  (:external-user-id identity)
     :id nil}))

(defn get-participant-id
  [db identity]
  (:id (registration-db/get-participant db (get-external-id-from-session identity))))

(defn get-or-create-participant
  [db session]
  (:id (registration-db/get-or-create-participant! db {:external_user_id (:external-user-id identity)
                                                       :email nil
                                                       :id nil})))

(defn- extract-nationalities
  [nationalities]
  (mapv (fn [[nat-code & _]] {:kansalaisuusKoodi nat-code}) nationalities))

(defn- extract-person-from-registration
  [{:keys [email first_name last_name gender lang nationalities birth_date]} ssn]
  (let [basic-fields {:yhteystieto    [{:yhteystietoTyyppi "YHTEYSTIETO_SAHKOPOSTI"
                                        :yhteystietoArvo   email}]
                      :etunimet       first_name
                      :kutsumanimi    first_name
                      :sukunimi       last_name
                      :sukupuoli      gender
                      :aidinkieli     {:kieliKoodi lang}
                      :asiointiKieli  {:kieliKoodi lang}
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
  (let [participant-id (get-or-create-participant db session)
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

(defn create-and-send-link [db url-helper email-q lang login-link template-data expires-in-days]
  (let [code          (str (UUID/randomUUID))
        login-url     (str (url-helper :yki.login-link.url) "?code=" code)
        expires-at    (t/plus (t/now) (t/days expires-in-days))
        email         (:email (registration-db/get-participant db {:id (:participant_id login-link)
                                                                   :external_user_id nil}))
        link-type     (:type login-link)
        hashed        (sha256-hash code)]
    (login-link-db/create-login-link! db
                                      (assoc login-link
                                             :expires_at expires-at
                                             :code hashed))
    (pgq/put email-q
             {:recipients [email]
              :subject (template-util/subject link-type lang)
              :body (template-util/render link-type lang (assoc template-data :login-url login-url))})))

(defn submit-registration
  [db url-helper email-q lang session id registration-form amount onr-client]
  (let [identity        (:identity session)
        participant-id  (get-participant-id db session)
        email           (:email registration-form)]
    (when email
      (registration-db/update-participant-email! db email participant-id))
    (let [registration-data (registration-db/get-registration-data db id participant-id lang)
          oid                 (or (:oid identity)
                                  (onr/get-or-create-person onr-client
                                                            (extract-person-from-registration registration-form (:ssn identity))))]
      (if (and registration-data oid)
        (let [payment                   {:registration_id id
                                         :lang lang
                                         :oid oid
                                         :amount amount}
              update-registration       {:id id
                                         :form registration-form
                                         :form_version 1
                                         :participant_id participant-id}
              payment-link              {:participant_id participant-id
                                         :exam_session_id nil
                                         :registration_id id
                                         :expired_link_redirect (url-helper :link-expired.redirect)
                                         :success_redirect (url-helper :payment-link.redirect id)
                                         :type "PAYMENT"}
              create-and-send-link-fn   #(create-and-send-link db
                                                               url-helper
                                                               email-q
                                                               lang
                                                               payment-link
                                                               (assoc registration-data :amount amount)
                                                               8)
              success                   (registration-db/create-payment-and-update-registration! db
                                                                                                 payment
                                                                                                 update-registration
                                                                                                 create-and-send-link-fn)]
          (if success
            oid
            (error "Registration" id "submit failed with data:" registration-data "and oid:" oid)))
        (error "Registration" id  "submit failed with data:" registration-data "and oid:" oid)))))
