(ns yki.handler.exam-date-public
  (:require [compojure.api.sweet :refer [context GET]]
            [yki.boundary.exam-date-db :as exam-date-db]
            [ring.util.http-response :refer [ok]]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-date-public [_ {:keys [db error-boundary]}]
  (context routing/exam-date-api-root []
    :coercion :spec
    :middleware [error-boundary]
    (GET "/" []
      :return ::ys/exam-date-response
      (ok {:dates (exam-date-db/get-exam-dates db)}))))
