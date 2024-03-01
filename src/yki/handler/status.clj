(ns yki.handler.status
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.reader.edn :as edn]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok internal-server-error]]
    [yki.boundary.status-db :as status-db]
    [yki.handler.routing :as routing]))

(defmethod ig/init-key :yki.handler/status [_ {:keys [db error-boundary]}]
  {:pre [(some? db) (some? error-boundary)]}
  (api
    (context routing/status-api-root []
      :middleware [error-boundary]
      (GET "/" []
        (if (status-db/get-status db)
          (ok {:success true})
          (internal-server-error)))
      (GET "/buildversion.txt" []
        (-> (jio/resource "buildversion.edn")
            (slurp)
            (edn/read-string)
            (ok))))))
