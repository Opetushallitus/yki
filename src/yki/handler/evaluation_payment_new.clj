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
        ; TODO As this is an unauthenticated endpoint, the redirect URL
        ;  could be called by interested third parties to collect personal
        ;  data along with information regarding evaluation request,
        ;  which could potentially be abused for eg. phishing campaigns.
        ;  Attempt to secure by requiring user to provide a signature?
        (if-let [{state :state
                  href  :href} (order-id->payment-data payment-helper id)]
          (if (= "UNPAID" state)
            (ok {:redirect href})
            (do (log/error "Payment not in unpaid state. Order id:" id "state:" state)
                (bad-request {:error "Payment not unpaid."})))
          (do (log/error "Could not find payment data corresponding to evaluation order with id" id)
              (bad-request {:error "Could not find payment data for order"})))))))
