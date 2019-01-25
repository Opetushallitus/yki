(ns yki.handler.exam-session-public
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.handler.routing :as routing]
            [ring.util.response :refer [response not-found]]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session-public [_ {:keys [db]}]
  {:pre [(some? db)]}
  (context routing/exam-session-public-api-root []
    :coercion :spec
    (GET "/" []
      :query-params [{from :- ::ys/date nil}]
      :return ::ys/exam-sessions-response
      (response {:exam_sessions (exam-session-db/get-exam-sessions db nil from)}))))

