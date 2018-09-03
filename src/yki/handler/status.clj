(ns yki.handler.status
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.status_db :as status-db]
            [yki.handler.routing :as routing]
            [yki.middleware.auth :as auth]
            [ring.util.response :refer [response status]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/status [_ {:keys [db auth]}]
  (api
   (context routing/status-api-root []
     (GET "/" {:as request}
       :middleware [auth auth/authenticated]
       (if (status-db/get-status db)
         (response {:success true})
         (status 500))))))
