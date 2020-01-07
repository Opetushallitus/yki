(ns yki.handler.post-admission
  (:require [compojure.api.sweet :refer [api context POST]]
            [yki.boundary.post-admission-db :as post-admission-db]
            [yki.handler.routing :as routing]
            [clojure.tools.reader.edn :as edn]
            [ring.util.http-response :refer [ok internal-server-error]]
            [integrant.core :as ig]
            [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/post-admission [_ {:keys [db]}]
  (api
    (context routing/post-admission-api-root []
      :coercion :spec
      (context "/:id" []
        (POST "/" request
          :path-params [id :- ::ys/exam_session_id]
          :body [post-admission ::ys/post-admission-request]
        ;:return ::ys/id-response
          (if (post-admission-db/upsert-post-admission db post-admission id)
            (ok {:success true})
            (internal-server-error)))))))
