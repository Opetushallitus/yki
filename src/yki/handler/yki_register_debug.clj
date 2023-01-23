(ns yki.handler.yki-register-debug
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok]]
    [yki.handler.routing :as routing]
    [yki.boundary.yki-register :refer [return-exam-session-participants-csv]]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/yki-register-debug [_ {:keys [access-log auth db url-helper]}]
  {:pre [(some? access-log)
         (some? auth)
         (some? db)
         (some? url-helper)]}
  (api
    (context routing/yki-register-debug-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id" _
        :path-params [id :- ::ys/id]
        (log/warn (str "Request yki-register CSV export for debug purposes for exam-session " id))
        (-> (ok (return-exam-session-participants-csv db url-helper id))
            (assoc-in [:headers "Content-Type"] "text/csv"))))))
