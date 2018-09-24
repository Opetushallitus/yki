(ns yki.handler.organizer
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

(defn- date? [maybe-date]
  (or (instance? DateTime maybe-date)
      (f/parse maybe-date)))

(s/def ::date (st/spec
               {:spec (partial date?)
                :type :date-time
                :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::email-type (s/and string? #(re-matches email-regex %)))
(s/def ::oid                  (s/and string? #(<= (count %) 256)))
(s/def ::agreement_start_date ::date)
(s/def ::agreement_end_date   ::date)
(s/def ::contact_name         (s/and string? #(<= (count %) 256)))
(s/def ::language_code        (s/and string? #(<= (count %) 2)))
(s/def ::level_code           (s/and string? #(<= (count %) 16)))
(s/def ::success              boolean?)
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
(s/def ::success-map (s/keys :req-un [::success]))

(defn- exists? [s]
  (not (nil? s)))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth files-handler]}]
  {:pre [(exists? db) (exists? url-helper) (exists? files-handler)]}
  (api
   (context routing/organizer-api-root []
     :middleware [auth]
     :coercion :spec
     (POST "/" []
       :body [organizer ::organizer-type]
       :return ::success-map
       (if (organizer-db/create-organizer! db organizer)
         (response {:success true})))
     (GET "/" []
       :return ::organizers-map
       (response {:organizers (organizer-db/get-organizers db)}))
     (context "/:oid" [oid]
       (PUT "/" []
         :body [organizer ::organizer-type]
         :return ::success-map
         (if (organizer-db/update-organizer! db oid organizer)
           (response {:success true})
           (not-found {:error "Organizer not found"})))
       (DELETE "/" []
         :return ::success-map
         (if (organizer-db/delete-organizer! db oid)
           (response {:success true})
           (not-found {:error "Organizer not found"})))
       (context "/files" []
         (files-handler oid))))))
