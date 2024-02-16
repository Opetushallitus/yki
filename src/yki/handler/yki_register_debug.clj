(ns yki.handler.yki-register-debug
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [internal-server-error ok]]
    [yki.handler.routing :as routing]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.yki-register :refer [return-exam-session-participants-csv
                                       sync-exam-session-and-organizer
                                       sync-exam-session-participants]]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/yki-register-debug [_ {:keys [access-log auth basic-auth db error-boundary url-helper]}]
  {:pre [(some? access-log)
         (some? auth)
         (some? basic-auth)
         (some? db)
         (some? error-boundary)
         (some? url-helper)]}
  (api
    (context routing/yki-register-debug-root []
      :coercion :spec
      :no-doc true
      :middleware [error-boundary auth access-log wrap-params]
      (GET "/:id" _
        :path-params [id :- ::ys/id]
        (log/warn (str "Request yki-register CSV export for debug purposes for exam-session " id))
        (-> (ok (return-exam-session-participants-csv db url-helper id))
            (assoc-in [:headers "Content-Type"] "text/csv")))
      (context "/sync/exam-session" []
        :coercion :spec
        :no-doc true
        ;:middleware [auth access-log wrap-params]
        (POST "/:id" _
          :path-params [id :- ::ys/id]
          (log/info "Manually forcing exam session" id "to be synced to Solki")
          (if-let [exam-session (exam-session-db/get-exam-session-by-id db id)]
            (if (sync-exam-session-and-organizer db url-helper basic-auth false {:type         "CREATED"
                                                                                 :exam-session exam-session})
              (ok {:success true})
              (internal-server-error))
            (log/error "Exam session with id" id "not found")))
        (POST "/:id/participants" _
          :path-params [id :- ::ys/id]
          (log/info "Manually forcing participants off exam session" id "to be synced to Solki")
          (if (sync-exam-session-participants db url-helper basic-auth false id)
            (ok {:success true})
            (internal-server-error)))))))
