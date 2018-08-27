(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer_db :as organizer_db]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok]]
            [ring.util.request]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]
            [integrant.core :as ig]))

(defn- date? [maybe-date]
  (c/to-date maybe-date))

(s/def ::oid                  (s/and string? #(<= (count %) 256)))
(s/def ::agreement_start_date date?)
(s/def ::agreement_end_date   date?)
(s/def ::contact_name         (s/and string? #(<= (count %) 256)))
(s/def ::contact_email        (s/and string? #(<= (count %) 256)))
(s/def ::contact_phone_number (s/and string? #(<= (count %) 256)))

(s/def ::x spec/int?)
(s/def ::y spec/int?)
(s/def ::total spec/int?)
(s/def ::total-map (s/keys :req-un [::total]))


(s/def ::organizer-spec (s/keys :req-un [::oid
                                    ::agreement_start_date
                                    ::agreement_end_date
                                    ::contact_name
                                    ::contact_email
                                    ::contact_phone_number]))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db]}]
  (api
    (context "/yki/api/organizer" []
      :coercion :spec
      (POST "/" []
        {:body [organizer ::organizer-spec]}
        (if (organizer_db/create-organizer! db organizer)
              (response {:success true})))
      (GET "/" []
        (response {:organizers (organizer_db/get-organizers db)}))
      (context "/:oid" [oid]
        (DELETE "/" []
          (if (organizer_db/delete-organizer! db oid)
            (response {:success true})
            (not-found {:error "Organizer not found"})))))))
