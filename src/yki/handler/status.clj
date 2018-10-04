(ns yki.handler.status
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.status_db :as status-db]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log :as access]
            [ring.util.response :refer [response status]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/status [_ {:keys [db]}]
  (api
   (context routing/status-api-root []
     (GET "/" []
       (if (status-db/get-status db)
         (response {:success true})
         (status 500))))))
