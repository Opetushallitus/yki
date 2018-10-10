
(ns yki.spec
  (:require
   [clj-time.format :as f]
   [clojure.spec.alpha :as s]
   [spec-tools.spec :as spec]
   [spec-tools.core :as st])

  (:import [org.joda.time DateTime]))

;; common
(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(def time-regex #"^([0-9]|0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")

(defn date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (f/parse maybe-date)))

(s/def ::date         (st/spec
                       {:spec (partial date?)
                        :type :date-time
                        :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::time         (s/and string? #(re-matches time-regex %)))
(s/def ::email-type   (s/and string? #(re-matches email-regex %)))
(s/def ::oid          (s/and string? #(<= (count %) 256)))
(s/def ::id           pos-int?)

;; organization
(s/def ::agreement_start_date ::date)
(s/def ::agreement_end_date   ::date)
(s/def ::contact_name         (s/and string? #(<= (count %) 256)))
(s/def ::language_code        (s/and string? #(=  (count %) 2)))
(s/def ::level_code           (s/and string? #(<= (count %) 16)))
(s/def ::success              boolean?)
(s/def ::error                string?)
(s/def ::contact_email        ::email-type)
(s/def ::contact_shared_email ::email-type)
(s/def ::contact_phone_number (s/and string? #(<= (count %) 256)))
(s/def ::language (s/keys :req-un [::language_code
                                   ::level_code]))
(s/def ::languages (s/or :null nil? :array (s/coll-of ::language)))
(s/def ::organizer-type (s/keys :req-un [::oid
                                         ::agreement_start_date
                                         ::agreement_end_date
                                         ::contact_name
                                         ::contact_email
                                         ::contact_phone_number]
                                :opt-un [::languages
                                         ::contact_shared_email]))
(s/def ::organizers (s/coll-of ::organizer-type))
(s/def ::organizers-response (s/keys :req-un [::organizers]))
(s/def ::response (s/keys :req-un [::success]
                          :opt-un [::error]))

;; exam-session-location
(s/def ::street_address       (s/and string? #(<= (count %) 256)))
(s/def ::city                 (s/and string? #(<= (count %) 256)))
(s/def ::other_location_info  (s/and string? #(<= (count %) 1024)))
(s/def ::extra_information    (s/and (s/nilable string?) #(<= (count %) 1024)))
(s/def ::exam_session_id      pos-int?)

(s/def ::exam-session-location (s/keys :req-un [::street_address
                                                ::city
                                                ::other_location_info
                                                ::extra_information
                                                ::language_code]))
(s/def ::location (s/coll-of ::exam-session-location))

;; exam-session
(s/def ::organizer_oid              ::oid)
(s/def ::session_date               ::date)
(s/def ::session_start_time         ::time)
(s/def ::session_end_time           ::time)
(s/def ::registration_start_date    ::date)
(s/def ::registration_start_time    ::time)
(s/def ::registration_end_date      ::date)
(s/def ::registration_end_time      ::time)
(s/def ::max_participants           pos-int?)
(s/def ::published_at               (s/nilable ::date))

(s/def ::from                       ::date)

(s/def ::exam-session (s/keys :req-un [::session_date
                                       ::session_start_time
                                       ::session_end_time
                                       ::language_code
                                       ::level_code
                                       ::registration_start_date
                                       ::registration_start_time
                                       ::registration_end_date
                                       ::registration_end_time
                                       ::max_participants
                                       ::published_at
                                       ::location]
                              :opt-un [::id
                                       ::organizer_oid]))
(s/def ::exam_sessions (s/coll-of ::exam-session))
(s/def ::exam-sessions-response (s/keys :req-un [::exam_sessions]))
(s/def ::from-param (s/keys :opt-un [::from]))

(s/def ::external_id (s/and string? #(<= (count %) 64)))
(s/def ::external-id-type (s/keys :req-un [::external_id]))

(s/def ::id-response (s/keys :req-un [::id]))

;; localisation
(s/def ::category (s/and string? #(<= (count %) 256)))
(s/def ::key      (s/and string? #(<=  (count %) 256)))
