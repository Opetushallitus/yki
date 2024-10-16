(ns yki.spec
  (:require
    [clj-time.format :as f]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [spec-tools.core :as st]
    [yki.util.common :refer [string->date]])
  (:import [org.joda.time DateTime LocalDate]))

;; common
(def time-regex #"^([0-9]|0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")
(def ssn-regexp #"([\d]{2})([\d]{2})([\d]{2})([+\-ABCDEFUVWXY])[\d]{3}[\dA-Za-z]")
(def oid-regex #"^([1-9][0-9]{0,3}|0)(\.([1-9][0-9]{0,30}|0)){3,13}$")

(defn ssn->date
  "Given a Finnish SSN string, returns a date corresponding to the date part and the intermediate character of the SSN.
   If the supplied string does not match the structure of a Finnish SSN or the date extracted from the string
   is not a valid date, returns nil instead.

   Does not attempt to validate the last four characters of an SSN."
  [ssn]
  (when-let [[_ dd mm yy intermediate-char] (re-matches ssn-regexp ssn)]
    (-> (case intermediate-char
          ("A" "B" "C" "D" "E" "F") 20
          ("-" "U" "V" "W" "X" "Y") 19
          "+" 18)
        (str yy "-" mm "-" dd)
        string->date)))

(defn- valid-email?
  "Attempts to validate an email address *without* allowing quoting,
   thus simplifying the validation rules quite a bit.
   Best effort, doesn't aim to cover all cases - might be simultaneously
   too restrictive as well as too lax.

   A proper validator such as the EmailValidator class from Apache Commons
   could also be used. However, proper validators typically perform too strict
   domain validation, rendering test addresses such as foo@test.invalid unusable.
   This custom implementation was chosen as a suitable middle ground.

   See https://datatracker.ietf.org/doc/html/rfc3696#section-3 for reference on
   the syntax available for email addresses."
  [email]
  {:pre [(string? email)]}
  (let [[local-part domain-part] (str/split email #"@" 2)]
    (and
      local-part
      domain-part
      ; Local part consists of max 64 whitelisted characters
      (re-matches #"^[\p{L}0-9\!#$%&'+\-\/=\?\^_`\.\{|\}~]{1,64}$" local-part)
      ; Consecutive periods not allowed
      (not (re-find #"\.\." local-part))
      ; Local part must not start with a period
      (not (str/starts-with? local-part "."))
      ; Local part must not end with a period
      (not (str/ends-with? local-part "."))
      ; Domain part consists of at most 255 characters
      (<= (count domain-part) 255)
      ; Domain part consists of at least two subdomains
      ; Each subdomain consists of max 63 whitelisted characters
      (let [subdomains (str/split domain-part #"\.")]
        (and (< 1 (count subdomains))
             (every? #(re-matches #"^[\p{L}0-9\-]{1,63}$" %) subdomains))))))

(defn- valid-ssn? [value]
  (or (str/blank? value)
      (ssn->date value)))

(s/def ::ssn (s/and (s/nilable string?)
                    valid-ssn?))

(s/def ::exam-language-code (s/and string? #(= (count %) 3)))
(s/def ::language-code #{"fi" "sv" "en"})
(s/def ::gender-code #{"" "1" "2"})

(defn date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (instance? LocalDate maybe-date)
      (f/parse maybe-date)))

(s/def ::non-blank-string (s/and string? #(not (str/blank? %)) #(<= (count %) 2560)))
(s/def ::registration-kind #{"POST_ADMISSION" "ADMISSION"})
(s/def ::date-type (st/spec
                     {:spec                (partial date?)
                      :type                :date-time
                      :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::time (s/and string? #(re-matches time-regex %)))
(s/def ::email-type (s/and string?
                           valid-email?))
(s/def ::oid (s/and string? #(re-matches oid-regex %)))
(s/def ::id pos-int?)
(s/def ::email ::email-type)
(s/def ::created ::date-type)
(s/def ::updated ::date-type)

;; organizer
(s/def ::agreement_start_date ::date-type)
(s/def ::agreement_end_date ::date-type)
(s/def ::contact_name (s/and string? #(<= (count %) 256)))
(s/def ::language_code ::exam-language-code)
(s/def ::level_code (s/and string? #(<= (count %) 16)))
(s/def ::success boolean?)
(s/def ::error string?)
(s/def ::contact_email ::email-type)
(s/def ::contact_shared_email (s/nilable ::email-type))
(s/def ::extra (s/and (s/nilable string?) #(<= (count %) 1024)))
(s/def ::contact_phone_number (s/and string? #(<= (count %) 256)))

(s/def ::language (s/keys :req-un [::language_code]
                          :opt-un [::level_code]))
(s/def ::exam-date-language (s/keys :req-un [::language_code
                                             ::level_code]))

(s/def ::languages (s/or :null nil? :array (s/coll-of ::language)))

(s/def ::organizer-type (s/keys :req-un [::oid
                                         ::agreement_start_date
                                         ::agreement_end_date
                                         ::contact_name
                                         ::contact_email
                                         ::contact_phone_number]
                                :opt-un [::languages
                                         ::extra]))

(s/def ::organizers (s/coll-of ::organizer-type))
(s/def ::organizers-response (s/keys :req-un [::organizers]))
(s/def ::response (s/keys :req-un [::success]
                          :opt-un [::error]))

;; quarantine
(s/def ::start_date ::date-type)
(s/def ::end_date ::date-type)
(s/def ::is_quarantined boolean?)
(s/def ::quarantined (s/keys :req-un [::is_quarantined]))
(s/def ::diary_number (s/and string? (complement str/blank?)))
(s/def :quarantine/email (s/nilable ::email))
(s/def :quarantine/phone_number (s/nilable ::phone_number))

(s/def ::quarantine-type (s/keys :req-un [::language_code
                                          ::start_date
                                          ::end_date
                                          (or ::birthdate ::ssn)
                                          ::first_name
                                          ::last_name
                                          ::diary_number]
                                 :opt-un [::created
                                          ::id
                                          :quarantine/email
                                          :quarantine/phone_number]))
(s/def ::quarantines (s/coll-of ::quarantine-type))
(s/def ::quarantine-response (s/keys :req-un [::quarantines]))

(s/def ::quarantine-match (s/keys :req-un [::language_code
                                           (or ::birthdate ::ssn)
                                           ::first_name
                                           ::last_name
                                           ::form
                                           ::registration_id
                                           ::id
                                           ::state]
                                  :opt-un [::email
                                           ::phone_number]))
(s/def ::quarantine_matches (s/coll-of ::quarantine-match))
(s/def ::quarantine-matches-response (s/keys :req-un [::quarantine_matches]))

(s/def ::quarantine_id ::id)

(s/def ::review (s/keys :req-un [::id
                                 ::is_quarantined
                                 ::quarantine_id
                                 ::registration_id
                                 ::updated
                                 ::exam_date
                                 ::language_code
                                 (or ::birthdate ::ssn)
                                 ::first_name
                                 ::last_name
                                 ::form
                                 ::state]
                        :opt-un [::email
                                 ::phone_number]))
(s/def ::reviews (s/coll-of ::review))
(s/def ::quarantine-reviews-response (s/keys :req-un [::reviews]))

;; exam-session-location
(s/def ::name (s/and string? #(<= (count %) 256)))
(s/def ::other_location_info (s/and string? #(<= (count %) 1024)))
(s/def ::lang ::language-code)
(s/def ::extra_information (s/and (s/nilable string?) #(<= (count %) 1024)))
(s/def ::exam_session_id pos-int?)

(s/def ::exam-session-location (s/keys :req-un [::name
                                                ::post_office
                                                ::zip
                                                ::street_address
                                                ::other_location_info
                                                ::extra_information
                                                ::lang]))
(s/def ::location (s/coll-of ::exam-session-location))



;; organizer-contact


(s/def :contact/name (s/nilable ::name))
(s/def :contact/email (s/nilable ::email))
(s/def :contact/phone_number (s/nilable ::phone_number))
(s/def ::organizer_id ::id)

(s/def ::contact-type (s/keys :req-un [(or :contact/name :contact/email :contact/phone_number)]
                              :opt-un [::organizer_id
                                       ::organizer_oid]))

;; exam-session
(s/def ::organizer_oid ::oid)
(s/def ::office_oid (s/nilable ::oid))
(s/def ::session_date ::date-type)
(s/def ::max_participants pos-int?)
(s/def ::published_at (s/nilable ::date-type))
(s/def ::participants int?)
(s/def ::exam_fee pos-int?)
(s/def ::open boolean?)
(s/def ::queue_full boolean?)
(s/def ::queue int?)
(s/def ::from ::date-type)
(s/def ::upcoming_admission boolean?)
; post admission extensions for exam-session
(s/def ::post_admission_quota (s/nilable pos-int?))
(s/def ::post_admission_start_date (s/nilable ::date-type))
(s/def ::post_admission_end_date (s/nilable ::date-type))
(s/def ::post_admission_active boolean?)
(s/def ::upcoming_post_admission boolean?)
; exam-session-contact
(s/def ::contact (s/nilable (s/coll-of ::contact-type)))
(s/def ::exam-session (s/keys :req-un [::session_date
                                       ::language_code
                                       ::level_code
                                       ::max_participants
                                       ::published_at
                                       ::location]
                              :opt-un [::id
                                       ::office_oid
                                       ::exam_fee
                                       ::contact
                                       ::open
                                       ::queue
                                       ::queue_full
                                       ::participants
                                       ::organizer_oid
                                       ::post_admission_quota
                                       ::post_admission_start_date
                                       ::post_admission_end_date
                                       ::post_admission_active
                                       ::upcoming_admission
                                       ::upcoming_post_admission]))

(s/def ::exam_sessions (s/coll-of ::exam-session))
(s/def ::exam-sessions-response (s/keys :req-un [::exam_sessions]))
(s/def ::exam_session_id pos-int?)
(s/def ::from-param (s/keys :opt-un [::from]))

(s/def ::post-admission-update (s/keys :req-un [::post_admission_start_date ::post_admission_end_date ::post_admission_quota]))

(s/def ::post-admission-activation (s/keys :req-un [::post_admission_quota]))

(s/def ::id-response (s/keys :req-un [::id]))

;; exam date
(s/def ::exam_date ::date-type)
(s/def ::registration_start_date ::date-type)
(s/def ::registration_end_date ::date-type)
(s/def ::post_admission_end_date (s/nilable ::date-type))
(s/def ::post_admission_enabled boolean?)
(s/def ::exam_session_count int?)
(s/def ::languages (s/or :null nil? :array (s/coll-of ::exam-date-language)))

(s/def ::exam-date-type (s/keys :req-un [::exam_date
                                         ::registration_start_date
                                         ::registration_end_date
                                         ::languages]
                                :opt-un [::id
                                         ::post_admission_start_date
                                         ::post_admission_end_date
                                         ::post_admission_enabled
                                         ::exam_session_count]))

(s/def ::dates (s/coll-of ::exam-date-type))
(s/def ::date ::exam-date-type)
(s/def ::single-exam-date-response (s/keys :req-un [::date]))
(s/def ::exam-date-response (s/keys :req-un [::dates]))

(s/def ::evaluation_start_date (s/nilable ::date-type))
(s/def ::evaluation_end_date (s/nilable ::date-type))
(s/def ::exam-date-evaluation (s/keys :req-un [::evaluation_start_date ::evaluation_end_date]))

(s/def ::days int?)

;; exam session queue


(s/def ::to-queue-request (s/keys :req-un [::email]))

;; login link
(s/def ::exam_session_id ::id)
(s/def ::user_data (s/and string? #(<= (count %) 2560)))

(s/def ::login-link (s/keys :req-un [::email
                                     ::exam_session_id]
                            :opt-un [::user_data]))

;; registration

(s/def ::first_name ::non-blank-string)
(s/def ::last_name ::non-blank-string)
(s/def ::nick_name (s/nilable ::non-blank-string))
(s/def ::gender (s/nilable ::gender-code))
(s/def ::nationalities (s/coll-of (s/and ::non-blank-string #(= (count %) 3))))
(s/def ::birthdate (s/nilable ::date-type))
(s/def ::post_office ::non-blank-string)
(s/def ::nationality_desc ::non-blank-string)
(s/def ::zip ::non-blank-string)
(s/def ::certificate_lang ::language-code)
(s/def ::exam_lang ::language-code)
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

(s/def ::exam_session ::exam-session)
(s/def ::registration-init (s/keys :req-un [::exam_session_id]))

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

(s/def ::is_strongly_identified boolean?)

(s/def ::registration-init-response (s/keys :req-un [::exam_session
                                                     ::is_strongly_identified
                                                     ::user
                                                     ::registration_id]))

;; exam session participant
(s/def ::state ::non-blank-string)
(s/def ::form ::registration)
(s/def ::kind ::registration-kind)
(s/def ::original_exam_session_id (s/nilable ::id))
(s/def ::original_exam_date (s/nilable ::exam_date))
(s/def ::exam-session-participant (s/keys :req-un [::created
                                                   ::form
                                                   ::registration_id
                                                   ::original_exam_session_id
                                                   ::original_exam_date
                                                   ::kind
                                                   ::state]))
(s/def :exam-session/participants (s/coll-of ::exam-session-participant))
(s/def ::participants-response (s/keys :req-un [:exam-session/participants]))

;; YKI register sync
(s/def :sync/type #{"CREATE" "UPDATE" "DELETE"})
(s/def :sync/created number?)
(s/def ::exam-session-id (s/nilable ::id))
(s/def ::organizer-oid (s/nilable ::oid))
(s/def ::activate boolean?)

(s/def ::data-sync-request (s/keys :req [:sync/type
                                         :sync/created]
                                   :opt [::exam-session-id
                                         ::organizer-oid]))

(s/def ::to_exam_session_id ::id)
(s/def ::relocate-request (s/keys :req-un [::to_exam_session_id]))

;; Re-evaluation period
(s/def ::evaluation_start_date ::date-type)
(s/def ::evaluation_end_date ::date-type)

(s/def ::evaluation-period (s/keys :req-un [::id
                                            ::exam_date
                                            ::evaluation_start_date
                                            ::evaluation_end_date
                                            ::level_code
                                            ::language_code]
                                   :opt-un [::open]))

(s/def ::evaluation_periods (s/coll-of ::evaluation-period))
(s/def ::evaluation-periods-response (s/keys :req-un [::evaluation_periods]))

(s/def ::first_names ::non-blank-string)
(s/def ::subtest-kind #{"READING" "LISTENING" "WRITING" "SPEAKING"})
(s/def ::subtests (s/coll-of ::subtest-kind))

(s/def ::evaluation-order (s/keys
                            :req-un [::first_names
                                     ::last_name
                                     ::email
                                     ::birthdate
                                     ::subtests]))

(s/def ::evaluation_order_id pos-int?)
(s/def ::signature ::non-blank-string)
(s/def ::redirect ::non-blank-string)
(s/def ::evaluation-order-response (s/keys :req-un [::evaluation_order_id
                                                    ::signature
                                                    ::redirect]))

(s/def ::evaluation-response (s/keys
                               :req-un [::id
                                        ::language_code
                                        ::level_code
                                        ::exam_date
                                        ::subtests]))

(s/def ::redirect-to (s/nilable ::non-blank-string))

(s/def ::auth-method #{"EMAIL" "SUOMIFI" "CAS"})
(s/def ::username ::non-blank-string)
(s/def ::identity (s/or ::not-authenticated nil?
                        ::email-identity (s/keys :req-un [::email])
                        ::suomi-identity (s/keys :req-un [::first_name ::last_name ::ssn])
                        ::cas-identity (s/keys :req-un [::username])))

(s/def ::user-identity-response (s/keys
                                 :req-un [::identity]
                                 :opt-un [::auth-method]))

(s/def ::exam_session_id pos-int?)
(s/def ::expires_at ::date-type)
(s/def ::open-registration (s/keys :req-un [::exam_session_id ::expires_at]))
(s/def ::open_registrations (s/coll-of ::open-registration) )

(s/def ::user-open-registrations-response (s/keys :req-un [::open_registrations]))

(s/def ::environment #{:dev :qa :prod})
