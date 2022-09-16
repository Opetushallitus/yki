(ns yki.handler.evaluation-payment-new
  (:require
    [compojure.api.sweet :refer [api context GET]]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [bad-request ok found]]
    [yki.handler.routing :as routing]
    [yki.boundary.evaluation-db :as evaluation-db]
    [yki.middleware.payment :refer [with-request-validation]]
    [yki.spec :as ys]
    [yki.util.evaluation-payment-helper :refer [order-id->payment-data]]))

(defmethod ig/init-key :yki.handler/evaluation-payment-new [_ {:keys [db auth access-log payment-helper url-helper email-q]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-helper) (some? url-helper) (some? email-q)]}
  (api
    (context routing/evaluation-payment-new-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" _
        :path-params [id :- ::ys/registration_id]
        ; TODO By this point, the payment transaction should already be created and all that's left is to redirect to Paytrail!
        (if-let [payment-data (order-id->payment-data payment-helper id)]
          (if (= "UNPAID" (:state payment-data))
            (ok {:redirect (:href payment-data)})
            (do (log/error "Payment not in unpaid state. Order id:" id "state:" (:state payment-data))
                (bad-request {:error "Payment not unpaid."})))
          (do (log/error "Could not find payment data corresponding to evaluation order with id" id)
              (bad-request {:error "Could not find payment data for order"})))))))
