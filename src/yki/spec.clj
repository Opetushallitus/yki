
(ns yki.spec
  (:require
   [clj-time.format :as f]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [spec-tools.spec :as spec]
   [spec-tools.core :as st])

  (:import [org.joda.time DateTime]))

;; common
(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(def amount-regexp #"\d{0,3}.\d{2}")
(def time-regex #"^([0-9]|0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")
(def ssn-regexp #"[\d]{6}[+\-A-Za-z][\d]{3}[\dA-Za-z]")
(def ssn-without-identifier-regexp #"[\d]{6}[+\-A-Za-z]")
(def oid-regex #"^([1-9][0-9]{0,3}|0)(\.([1-9][0-9]{0,30}|0)){3,13}$")

(s/def ::ssn (s/and string? #(re-matches ssn-regexp %)))
(s/def ::amount (s/and string? #(re-matches amount-regexp %)))
(s/def ::exam-language-code (s/and string? #(= (count %) 3)))
(s/def ::language-code  #{"fi" "sv" "en"})
(s/def ::gender-code  #{"" "1" "2"})

(defn date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (f/parse maybe-date)))

(s/def ::non-blank-string (s/and string? #(not (str/blank? %)) #(<= (count %) 2560)))
(s/def ::date         (st/spec
                       {:spec (partial date?)
                        :type :date-time
                        :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::time         (s/and string? #(re-matches time-regex %)))
(s/def ::email-type   (s/and string? #(re-matches email-regex %)))
(s/def ::oid          (s/and string? #(re-matches oid-regex %)))
(s/def ::id           pos-int?)
(s/def ::email        ::email-type)

;; organizer
(s/def ::agreement_start_date ::date)
(s/def ::agreement_end_date   ::date)
(s/def ::created              ::date)
(s/def ::contact_name         (s/and string? #(<= (count %) 256)))
(s/def ::language_code        ::exam-language-code)
(s/def ::level_code           (s/and string? #(<= (count %) 16)))
(s/def ::success              boolean?)
(s/def ::error                string?)
(s/def ::contact_email        ::email-type)
(s/def ::contact_shared_email (s/nilable ::email-type))
(s/def ::extra                (s/and (s/nilable string?) #(<= (count %) 1024)))
(s/def ::contact_phone_number (s/and string? #(<= (count %) 256)))
(s/def ::external_id          (s/and string? #(<= (count %) 64)))
(s/def ::external-id-type     (s/keys :req-un [::external_id]))
(s/def ::language             (s/keys :req-un [::language_code]
                                      :opt-un [::level_code]))
(s/def ::attachment           (s/keys :req-un [::external_id
                                               ::created]))
(s/def ::languages            (s/or :null nil? :array (s/coll-of ::language)))
(s/def ::attachments          (s/or :null nil? :array (s/coll-of ::attachment)))
(s/def ::merchant_id          (s/nilable pos-int?))
(s/def ::merchant_secret      (s/nilable (s/and string? #(<= (count %) 30))))
(s/def ::merchant             (s/nilable (s/keys :req-un [::merchant_id ::merchant_secret])))
(s/def ::organizer-type       (s/keys :req-un [::oid
                                               ::agreement_start_date
                                               ::agreement_end_date
                                               ::contact_name
                                               ::contact_email
                                               ::contact_phone_number]
                                      :opt-un [::languages
                                               ::extra
                                               ::merchant]))

(s/def ::organizers (s/coll-of ::organizer-type))
(s/def ::organizers-response (s/keys :req-un [::organizers]))
(s/def ::response (s/keys :req-un [::success]
                          :opt-un [::error]))

;; exam-session-location
(s/def ::name                 (s/and string? #(<= (count %) 256)))
(s/def ::other_location_info  (s/and string? #(<= (count %) 1024)))
(s/def ::lang                 ::language-code)
(s/def ::extra_information    (s/and (s/nilable string?) #(<= (count %) 1024)))
(s/def ::exam_session_id      pos-int?)

(s/def ::exam-session-location (s/keys :req-un [::name
                                                ::post_office
                                                ::zip
                                                ::street_address
                                                ::other_location_info
                                                ::extra_information
                                                ::lang]))
(s/def ::location (s/coll-of ::exam-session-location))

;; exam-session
(s/def ::organizer_oid              ::oid)
(s/def ::office_oid                 (s/nilable ::oid))
(s/def ::session_date               ::date)
(s/def ::max_participants           pos-int?)
(s/def ::published_at               (s/nilable ::date))
(s/def ::participants               int?)
(s/def ::exam_fee                   ::amount)
(s/def ::open                       boolean?)
(s/def ::queue_full                 boolean?)
(s/def ::queue                      int?)
(s/def ::from                       ::date)
; post admission extensions for exam-session
(s/def ::post_admission_quota      (s/nilable pos-int?))
(s/def ::post_admission_start_date (s/nilable ::date))
(s/def ::post_admission_active     boolean?)

(s/def ::exam-session (s/keys :req-un [::session_date
                                       ::language_code
                                       ::level_code
                                       ::max_participants
                                       ::published_at
                                       ::location]
                              :opt-un [::id
                                       ::office_oid
                                       ::exam_fee
                                       ::open
                                       ::queue
                                       ::queue_full
                                       ::participants
                                       ::organizer_oid
                                       ::post_admission_quota
                                       ::post_admission_start_date
                                       ::post_admission_active]))
(s/def ::exam_sessions (s/coll-of ::exam-session))
(s/def ::exam-sessions-response (s/keys :req-un [::exam_sessions]))
(s/def ::from-param (s/keys :opt-un [::from]))

(s/def ::id-response (s/keys :req-un [::id]))

;; exam date
(s/def ::exam_date                 ::date)
(s/def ::registration_start_date   ::date)
(s/def ::registration_end_date     ::date)
(s/def ::post_admission_end_date   (s/nilable ::date))
(s/def ::exam-date-type (s/keys :req-un [::exam_date
                                         ::registration_start_date
                                         ::registration_end_date
                                         ::post_admission_end_date
                                         ::languages]))
(s/def ::dates (s/coll-of ::exam-date-type))
(s/def ::exam-date-response (s/keys :req-un [::dates]))

;; exam session queue
(s/def ::to-queue-request (s/keys :req-un [::email]))

;; localisation
(s/def ::category (s/and string? #(<= (count %) 256)))
(s/def ::key      (s/and string? #(<= (count %) 256)))

;; login link
(s/def ::exam_session_id        ::id)
(s/def ::user_data              (s/and string? #(<= (count %) 2560)))

(s/def ::login-link (s/keys :req-un [::email
                                     ::exam_session_id]
                            :opt-un [::user_data]))

(defn- parse-int [int-str]
  (try (Integer/parseInt int-str)
       (catch Throwable _)))

;; payment
(def pt-order-number-regex #"/^[0-9a-zA-Z()\[\]{}*+\-_,. ]{1,64}$/")
(def pt-locale-regexp #"^[a-z]{1,2}[_][A-Z]{1,2}$")

(s/def ::timestamp date?)
(s/def ::order-number (s/and ::non-blank-string #(< (count %) 33)))
(s/def ::msg ::non-blank-string)
(s/def ::payment-id (s/and ::non-blank-string #(< (count %) 26)))
(s/def ::uri ::non-blank-string)

(s/def ::pt-payment-params (s/keys :req [::language-code
                                         ::order-number
                                         ::reference-number]))

(s/def ::MERCHANT_ID number?)
(s/def ::LOCALE (s/and string? #(re-matches pt-locale-regexp %)))
(s/def ::URL_SUCCESS ::non-blank-string)
(s/def ::URL_CANCEL ::non-blank-string)
(s/def ::AMOUNT (s/and string? #(re-matches amount-regexp %)))
(s/def ::ORDER_NUMBER ::order-number)
(s/def ::MSG_SETTLEMENT_PAYER ::non-blank-string)
(s/def ::MSG_UI_MERCHANT_PANEL ::non-blank-string)
(s/def ::PARAMS_IN ::non-blank-string)
(s/def ::PARAMS_OUT ::non-blank-string)
(s/def ::AUTHCODE ::non-blank-string)

(s/def ::params (s/keys :req-un [::MERCHANT_ID
                                 ::LOCALE
                                 ::URL_SUCCESS
                                 ::URL_CANCEL
                                 ::AMOUNT
                                 ::ORDER_NUMBER
                                 ::MSG_SETTLEMENT_PAYER
                                 ::MSG_UI_MERCHANT_PANEL
                                 ::PARAMS_IN
                                 ::PARAMS_OUT
                                 ::AUTHCODE]))

(s/def ::pt-payment-form-data (s/keys :req-un [::uri
                                               ::params]))

;; registration

(s/def ::first_name ::non-blank-string)
(s/def ::last_name ::non-blank-string)
(s/def ::nick_name (s/nilable ::non-blank-string))
(s/def ::gender (s/nilable ::gender-code))
(s/def ::nationalities (s/coll-of (s/and ::non-blank-string #(= (count %) 3))))
(s/def ::birthdate (s/nilable ::date))
(s/def ::post_office ::non-blank-string)
(s/def ::nationality_desc ::non-blank-string)
(s/def ::zip ::non-blank-string)
(s/def ::certificate_lang ::language-code)
(s/def ::exam_lang ::language-code)
(s/def ::email ::email-type)
(s/def ::street_address ::non-blank-string)
(s/def ::phone_number ::non-blank-string)

(s/def ::registration (s/keys
                       :req-un [::first_name
                                ::last_name
                                ::nationalities
                                ::certificate_lang
                                ::exam_lang
                                (or ::birthdate ::ssn)
                                ::post_office
                                ::zip
                                ::street_address
                                ::phone_number
                                ::email]
                       :opt-un [::gender
                                ::nationality_desc]))

(s/def ::registration-init (s/keys :req-un [::exam_session_id]))

(s/def ::exam_session ::exam-session)
(s/def ::registration_id ::id)

(s/def :user/first_name (s/nilable string?))
(s/def :user/last_name (s/nilable string?))
(s/def :user/nick_name (s/nilable string?))
(s/def :user/post_office (s/nilable string?))
(s/def :user/zip (s/nilable string?))
(s/def :user/ssn (s/nilable ::ssn))
(s/def :user/street_address (s/nilable string?))
(s/def :user/email (s/nilable ::email-type))

(s/def ::user (s/keys :opt-un [:user/first_name
                               :user/last_name
                               :user/nick_name
                               :user/ssn
                               :user/post_office
                               :user/zip
                               :user/street_address
                               :user/email]))

(s/def ::registration-init-response (s/keys :req-un [::exam_session
                                                     ::user
                                                     ::registration_id]))

;; exam session participant
(s/def ::state                    ::non-blank-string)
(s/def ::form                     ::registration)
(s/def ::original_exam_session_id (s/nilable ::id))
(s/def :participant/order_number  (s/nilable ::order-number))
(s/def :participant/created       (s/nilable ::created))
(s/def ::exam-session-participant (s/keys :req-un [::form
                                                   ::registration_id
                                                   :participant/created
                                                   :participant/order_number
                                                   ::original_exam_session_id
                                                   ::state]))
(s/def :exam-session/participants (s/coll-of ::exam-session-participant))
(s/def ::participants-response    (s/keys :req-un [:exam-session/participants]))

;; YKI register sync
(s/def :sync/type         #{"CREATE" "UPDATE" "DELETE"})
(s/def :sync/created      number?)
(s/def ::exam-session-id  (s/nilable ::id))
(s/def ::organizer-oid    (s/nilable ::oid))
(s/def ::activate boolean?)

(s/def ::data-sync-request (s/keys :req [:sync/type
                                         :sync/created]
                                   :opt [::exam-session-id
                                         ::organizer-oid]))

(s/def ::to_exam_session_id ::id)
(s/def ::relocate-request (s/keys :req-un [::to_exam_session_id]))
