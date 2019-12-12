(ns yki.handler.post-admission
  (:require [compojure.api.sweet :refer [api context GET]]
            [yki.boundary.post-admission-db :as post-admission-db]
            [yki.handler.routing :as routing]
            [clojure.tools.reader.edn :as edn]
            [ring.util.http-response :refer [ok internal-server-error]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/post-admission [_ {:keys [db]}]
  (api
    (context routing/post-admission-api-root []
      (GET "/" []
        (if (post-admission-db/do-the-needful db) ; TODO: the actual stuff goes here, this is just a copy of status api
          (ok {:success true})
          (internal-server-error))))))
