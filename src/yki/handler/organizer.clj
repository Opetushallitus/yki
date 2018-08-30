(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer_db :as organizer-db]
            [yki.handler.routing :as routing]
            [clj-time.format :as f]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok]]
            [ring.util.request]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]
            [spec-tools.core :as st]
            [integrant.core :as ig])

  (:import (org.joda.time DateTime)))

(defn- date? [maybe-date]
  (f/parse maybe-date))

(s/def ::date (st/spec
               {:spec (partial date?)
                :type :date-time
                :json-schema/default "2018-01-01T00:00:00Z"}))
(s/def ::oid                  (s/and string? #(<= (count %) 256)))
(s/def ::agreement_start_date ::date)
(s/def ::agreement_end_date   ::date)
(s/def ::contact_name         (s/and string? #(<= (count %) 256)))
(s/def ::language_code        (s/and string? #(<= (count %) 2)))
(s/def ::level_code           (s/and string? #(<= (count %) 16)))
(s/def ::contact_email        (s/and string? #(<= (count %) 256)))
(s/def ::contact_phone_number (s/and string? #(<= (count %) 256)))
(s/def ::language (s/keys :req-un [::language_code
                                   ::level_code]))
(s/def ::languages (s/coll-of ::language :kind vector?))
(s/def ::organizer-spec (s/keys :req-un [::oid
                                         ::agreement_start_date
                                         ::agreement_end_date
                                         ::contact_name
                                         ::contact_email
                                         ::contact_phone_number]
                                :opt-un [::languages]))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db]}]
  (api
   (context routing/organizer-api-root []
     :coercion :spec
     (POST "/" []
       {:body [organizer ::organizer-spec]}
       (if (organizer-db/create-organizer! db organizer)
         (response {:success true})))
     (GET "/" []
       (response {:organizers (organizer-db/get-organizers db)}))
     (context "/:oid" [oid]
       (PUT "/" []
         {:body [organizer ::organizer-spec]}
         (if (organizer-db/update-organizer! db oid organizer)
           (response {:success true})
           (not-found {:error "Organizer not found"})))
       (DELETE "/" []
         (if (organizer-db/delete-organizer! db oid)
           (response {:success true})
           (not-found {:error "Organizer not found"})))))))
