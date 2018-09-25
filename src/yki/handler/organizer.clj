(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer_db :as organizer-db]
            [yki.handler.routing :as routing]
            [yki.spec :as ys]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok bad-request]]
            [ring.util.request]
            [ring.middleware.multipart-params :as mp]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth file-handler]}]
  (api
   (context routing/organizer-api-root []
     :middleware [auth]
     :coercion :spec
     (POST "/" []
       :body [organizer ::ys/organizer-type]
       :return ::ys/success-map
       (if (organizer-db/create-organizer! db organizer)
         (response {:success true})))
     (GET "/" []
       :return ::ys/organizers-map
       (response {:organizers (organizer-db/get-organizers db)}))
     (context "/:oid" [oid]
       (PUT "/" []
         :body [organizer ::ys/organizer-type]
         :return ::ys/success-map
         (if (organizer-db/update-organizer! db oid organizer)
           (response {:success true})
           (not-found {:success false
                       :error "Organizer not found"})))
       (DELETE "/" []
         :return ::ys/success-map
         (if (organizer-db/delete-organizer! db oid)
           (response {:success true})
           (not-found {:error "Organizer not found"})))
       (context "/file" []
         (file-handler oid))))))
