
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
(def time-regex #"^([0-9]|0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")

(s/def ::language-code (s/and string? #(= (count %) 2)))
(s/def ::yki-language-code  #{"fi" "sv" "en"})

(defn date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (f/parse maybe-date)))

(s/def ::non-blank-string (s/and string? #(not (str/blank? %))))
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
(s/def ::language_code        ::language-code)
(s/def ::level_code           (s/and string? #(<= (count %) 16)))
(s/def ::success              boolean?)
(s/def ::error                string?)
(s/def ::contact_email        ::email-type)
(s/def ::contact_shared_email (s/nilable ::email-type))
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
(s/def ::max_participants           pos-int?)
(s/def ::published_at               (s/nilable ::date))

(s/def ::from                       ::date)

(s/def ::exam-session (s/keys :req-un [::session_date
                                       ::language_code
                                       ::level_code
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
(s/def ::key      (s/and string? #(<= (count %) 256)))

;; login link
(s/def ::email                  ::email-type)
(s/def ::exam_session_id        ::id)
(s/def ::expired_link_redirect  (s/and string? #(<= (count %) 256)))
(s/def ::success_redirect       (s/and string? #(<= (count %) 256)))
(s/def ::user_data              (s/and string? #(<= (count %) 2560)))
(s/def ::expires_at             ::date)

(s/def ::login-link (s/keys :req-un [::email
                                     ::exam_session_id
                                     ::expired_link_redirect
                                     ::success_redirect
                                     ::expires_at]
                            :opt-un [::user_data]))

(defn- parse-int [int-str]
  (try (Integer/parseInt int-str)
       (catch Throwable _)))

(defn- valid-reference-number? [x]
  (let [factors [7 3 1]
        ref-str (str x)
        check-num (-> (last ref-str) str parse-int)
        ref (drop-last ref-str)]
    (-> (->> (reverse ref)
             (partition 3 3 [])
             (map (fn [three]
                    (->> (map #(parse-int (str %)) three)
                         (map * factors))))
             (flatten)
             (reduce +)
             str
             last
             str
             parse-int
             (- 10))
        (mod 10)
        (= check-num))))

;; payment
(def pt-order-number-regex #"/^[0-9a-zA-Z()\[\]{}*+\-_,. ]{1,64}$/")
(def pt-amount-regexp #"\d{0,3}.\d{2}")
(def pt-locale-regexp #"^[a-z]{1,2}[_][A-Z]{1,2}$")

(s/def ::timestamp date?)
(s/def ::amount (s/and string? #(re-matches pt-amount-regexp %)))
(s/def ::reference-number (s/and number? #(valid-reference-number? %)))
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
(s/def ::AMOUNT (s/and string? #(re-matches pt-amount-regexp %)))
(s/def ::ORDER_NUMBER ::order-number)
(s/def ::REFERENCE_NUMBER ::reference-number)
(s/def ::MSG_SETTLEMENT_PAYER ::non-blank-string)
(s/def ::MSG_UI_MERCHANT_PANEL ::non-blank-string)
(s/def ::PARAMS_IN ::non-blank-string)
(s/def ::PARAMS_OUT ::non-blank-string)
(s/def ::AUTHCODE ::non-blank-string)

(s/def ::pt-payment-form-params (s/keys :req-un [::MERCHANT_ID
                                                 ::LOCALE
                                                 ::URL_SUCCESS
                                                 ::URL_CANCEL
                                                 ::AMOUNT
                                                 ::ORDER_NUMBER
                                                 ::REFERENCE_NUMBER
                                                 ::MSG_SETTLEMENT_PAYER
                                                 ::MSG_UI_MERCHANT_PANEL
                                                 ::PARAMS_IN
                                                 ::PARAMS_OUT
                                                 ::AUTHCODE]))
          ; (s/def ::organizers-response (s/keys :req-un [::organizers]))
(s/def ::pt-payment-form-data (s/keys :req-un [::uri
                                               ::pt-payment-form-params]))
