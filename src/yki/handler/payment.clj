(ns yki.handler.payment
  (:require [compojure.api.sweet :refer :all]
            [clojure.tools.logging :refer [info error]]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.util.audit-log :as audit]
            [yki.spec :as ys]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error found]]
            [integrant.core :as ig]))

(defn- success-redirect [url-helper]
  (found (url-helper :payment.success-redirect)))

(defn- error-redirect [url-helper]
  (found (url-helper :payment.error-redirect)))

(defn- cancel-redirect [url-helper]
  (found (url-helper :payment.cancel-redirect)))

(defn- handle-exceptions [url-helper f]
  (try
    (f)
    (catch Exception e
      (error e "Payment handling failed")
      (error-redirect url-helper))))

(defmethod ig/init-key :yki.handler/payment [_ {:keys [db auth access-log payment-config url-helper email-q]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-config) (some? url-helper) (some? email-q)]}
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
     (GET "/success" request
       (let [params (:params request)]
         (info "Received payment success params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-return-params? db params)
                               (do
                                 (paytrail-payment/handle-payment-return db email-q params)
                                 (audit/log-participant {:request request
                                                         :target-kv {:k audit/payment
                                                                     :v (:ORDER_NUMBER params)}
                                                         :change {:type audit/create-op
                                                                  :new params}})
                                 (success-redirect url-helper))
                               (error-redirect url-helper)))))
     (GET "/cancel" request
       (let [params (:params request)]
         (info "Received payment cancel params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-return-params? db params)
                               (do
                                 (audit/log-participant {:request request
                                                         :target-kv {:k audit/payment
                                                                     :v (:ORDER_NUMBER params)}
                                                         :change {:type audit/cancel-op
                                                                  :new params}})
                                 (cancel-redirect url-helper))
                               (error-redirect url-helper)))))
     (GET "/notify" {params :params}
       (info "Received payment notify params" params)
       (if (paytrail-payment/valid-return-params? db params)
         (do
           (paytrail-payment/handle-payment-return db email-q params)
           (ok "OK"))
         (internal-server-error "Error in payment notify handling"))))))
