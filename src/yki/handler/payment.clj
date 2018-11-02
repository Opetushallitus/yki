(ns yki.handler.payment
  (:require [compojure.api.sweet :refer :all]
            [clojure.tools.logging :refer [info error]]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.util.audit-log :as audit-log]
            [yki.spec :as ys]
            [yki.payment.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error found]]
            [integrant.core :as ig]))

(defn- success-redirect [url-helper]
  (found (url-helper :payment.success-redirect)))

(defn- error-redirect [url-helper]
  (found (url-helper :payment.error-redirect)))

(defn- cancel-redirect [url-helper]
  (found (url-helper :payment.cancel-redirect)))

(defmethod ig/init-key :yki.handler/payment [_ {:keys [db auth access-log payment-config url-helper]}]
  (api
   (context routing/payment-root []
     :coercion :spec
     :middleware [auth access-log]
     (GET "/formdata" {session :session}
       :query-params [registration-id :- ::ys/id {lang :- ::ys/yki-language-code "fi"}]
       :return ::ys/pt-payment-form-data
       (let [external-user-id (get-in session [:identity :external-user-id])
             formdata (paytrail-payment/create-payment-form-data db payment-config registration-id external-user-id lang)]
         (if formdata
           (ok formdata)
           (internal-server-error {:error "Payment form data creation failed"}))))
     (GET "/success" {session :session query-params :query-params}
       (info "Received success params" query-params)
       (if (paytrail-payment/valid-return-params? payment-config query-params)
         (success-redirect url-helper)
         (error-redirect url-helper)))
     (GET "/cancel" {session :session query-params :query-params}
       (info "Received cancel params" query-params)
       (if (paytrail-payment/valid-return-params? payment-config query-params)
         (cancel-redirect url-helper)
         (error-redirect url-helper)))
     (GET "/notify" {session :session query-params :query-params}
       (info "Received notify params" query-params)
       (if (paytrail-payment/valid-return-params? payment-config query-params)
         (found (""))
         (found ("")))))))
