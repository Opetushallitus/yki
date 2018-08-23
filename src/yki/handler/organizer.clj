(ns yki.handler.organizer
  (:require [compojure.core :refer :all]
            [yki.boundary.organizer_db :as organizer_db]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [ring.util.response :refer [response not-found header]]
            [ring.util.request]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db]}]
  (context "/organizer" []
    (POST "/" request
      (if (organizer_db/create-organizer! db (:body-params request))
        (response {:success true})))
    (GET "/" []
      (response {:organizations (organizer_db/get-organizers db)}))
    (context "/:oid" [oid]
      (DELETE "/" []
        (if (organizer_db/delete-organizer! db oid)
          (response {:success true})
          (not-found {:error "Organizer not found"}))))))
