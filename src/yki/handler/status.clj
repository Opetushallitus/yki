(ns yki.handler.status
  (:require [compojure.api.sweet :refer [api context GET]]
            [yki.boundary.status-db :as status-db]
            [yki.handler.routing :as routing]
            [clojure.tools.reader.edn :as edn]
            [ring.util.http-response :refer [ok internal-server-error]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/status [_ {:keys [db]}]
  (api
   (context routing/status-api-root []
     (GET "/" []
       (if (status-db/get-status db)
         (ok {:success true})
         (internal-server-error)))
     (GET "/buildversion.txt" []
       (ok (edn/read-string (slurp (clojure.java.io/resource "buildversion.edn"))))))))
