(ns yki.handler.exam-session-public
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.handler.routing :as routing]
            [ring.util.http-response :refer [ok]]
            [ring.util.request]
            [yki.spec :as ys]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session-public [_ {:keys [db]}]
  {:pre [(some? db)]}
  (context routing/exam-session-public-api-root []
    :coercion :spec
    (GET "/" []
      :query-params [{from :- ::ys/date nil}]
      :return ::ys/exam-sessions-response
      (ok {:exam_sessions (exam-session-db/get-exam-sessions db nil from)}))
    (context "/:id" []
      (GET "/" []
        :return ::ys/exam-session
        :path-params [id :- ::ys/id]
        (ok (exam-session-db/get-exam-session-by-id db id)))
      (POST "/queue" []
        :path-params [id :- ::ys/id]
        :query-params [lang :- ::ys/language-code]
        :body [request ::ys/to-queue-request]
        :return ::ys/response
        (log/info "Adding email " (:email request) " to exam session " id " queue")
        (exam-session-db/add-to-exam-session-queue! db (:email request) lang id)
        (ok {:success true})))))

