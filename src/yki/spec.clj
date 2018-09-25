
(ns yki.spec
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer_db :as organizer-db]
            [yki.boundary.files :as files]
            [yki.handler.routing :as routing]
            [clj-time.format :as f]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok bad-request]]
            [ring.util.request]
            [ring.middleware.multipart-params :as mp]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]
            [spec-tools.core :as st]
            [integrant.core :as ig])

  (:import [org.joda.time DateTime]))

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(def time-regex #"^([0-9]|0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")

(defn- date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (f/parse maybe-date)))

(s/def ::date (st/spec
               {:spec (partial date?)
                :type :date-time
                :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::time (s/and string? #(re-matches time-regex %)))
(s/def ::email-type (s/and string? #(re-matches email-regex %)))
(s/def ::oid                  (s/and string? #(<= (count %) 256)))

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
(s/def ::organizers-map (s/keys :req-un [::organizers]))
(s/def ::response (s/keys :req-un [::success]
                          :opt-un [::error]))

;; exam-session
(s/def ::organizer_id               ::oid)
(s/def ::session_date               ::date)
(s/def ::session_start_time         ::time)
(s/def ::session_end_time           ::time)
(s/def ::registration_start_date    ::date)
(s/def ::registration_start_time    ::time)
(s/def ::registration_end_date      ::date)
(s/def ::registration_end_time      ::time)
(s/def ::max_participants           pos-int?)
(s/def ::published_at               ::date)

(s/def ::exam-session (s/and
                       (s/keys :req [::organizer_id
                                     ::session_date
                                     ::session_start_time
                                     ::session_end_time
                                     ::registration_start_date
                                     ::registration_start_time
                                     ::registration_end_date
                                     ::registration_end_time
                                     ::max_participants
                                     ::published_at])))

;; exam-session-location
(s/def ::street_address       (s/and string? #(<= (count %) 256)))
(s/def ::city                 (s/and string? #(<= (count %) 256)))
(s/def ::other_location_info  (s/and string? #(<= (count %) 1024)))
(s/def ::extra_information    (s/and string? #(<= (count %) 1024)))
(s/def ::exam_session_id      pos-int?)

(s/def ::external_id (s/and string? #(<= (count %) 64)))
(s/def ::external-id-type (s/keys :req-un [::external_id]))
