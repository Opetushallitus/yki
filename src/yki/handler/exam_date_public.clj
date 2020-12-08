(ns yki.handler.exam-date-public
  (:require [compojure.api.sweet :refer [context GET DELETE POST]]
            [yki.boundary.exam-date-db :as exam-date-db]
            [ring.util.http-response :refer [ok not-found]]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-date-public [_ {:keys [db]}]
  (context routing/exam-date-api-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/exam-date-response
      (ok {:dates (exam-date-db/get-exam-dates db)}))))
