(ns yki.handler.evaluation-period
  (:require [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [yki.boundary.evaluation-period-db :as evaluation-period-db]
            [yki.handler.routing :as routing]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [conflict internal-server-error ok]]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/evaluation-period [_ {:keys [db]}]
  (context routing/evaluation-period-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/evaluation-periods-response
      (ok {:evaluation_periods (evaluation-period-db/get-upcoming-evaluation-periods db)}))))
