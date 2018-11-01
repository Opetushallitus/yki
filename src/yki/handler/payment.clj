(ns yki.handler.payment
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.util.audit-log :as audit-log]
            [yki.spec :as ys]
            [yki.payment.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/payment [_ {:keys [db auth access-log payment-config]}]
  (api
   (context routing/payment-root []
     :coercion :spec
     :middleware [auth access-log]
     (GET "/formdata" request
       :query-params [registration-id :- ::ys/id {lang :- ::ys/yki-language-code "fi"}]
       :return ::ys/pt-payment-form-data
       (let [external-user-id (get-in request [:session :identity :external-user-id])
             formdata (paytrail-payment/create-payment-form-data db payment-config registration-id external-user-id lang)]
         (if formdata
           (ok formdata)
           (internal-server-error {:error "Payment form data creation failed"})))))))
