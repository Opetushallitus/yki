(ns yki.handler.payment
  (:require [compojure.api.sweet :refer [api context GET]]
            [clojure.tools.logging :as log]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.util.audit-log :as audit]
            [yki.spec :as ys]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error found]]
            [integrant.core :as ig]))

(defn- success-redirect [url-helper lang exam-session-id]
  (found (url-helper :payment.success-redirect lang exam-session-id)))

(defn- error-redirect [url-helper lang]
  (found (url-helper :payment.error-redirect lang)))

(defn- cancel-redirect [url-helper lang]
  (found (url-helper :payment.cancel-redirect lang)))

(defn- handle-exceptions [url-helper f]
  (try
    (f)
    (catch Exception e
      (log/error e "Payment handling failed")
      (error-redirect url-helper))))

(defmethod ig/init-key :yki.handler/payment [_ {:keys [db auth access-log payment-config url-helper email-q]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-config) (some? url-helper) (some? email-q)]}
  (api
   (context routing/payment-root []
     :coercion :spec
     :no-doc true
     :middleware [auth access-log]
     (GET "/formdata" {session :session}
       :query-params [registration-id :- ::ys/id {lang :- ::ys/language-code "fi"}]
       :return ::ys/pt-payment-form-data
       (let [external-user-id (get-in session [:identity :external-user-id])
             formdata (paytrail-payment/create-payment-form-data db url-helper payment-config registration-id external-user-id lang)]
         (if formdata
           (do
             (log/info "Get payment form data success")
             (ok formdata))
           (internal-server-error {:error "Payment form data creation failed"}))))
     (GET "/success" request
       (let [params (:params request)]
         (log/info "Received payment success params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-return-params? db params)
                               (let [payment (paytrail-payment/get-payment db params)
                                     lang (or (:lang payment) "fi")]
                                 (if (paytrail-payment/handle-payment-return db email-q url-helper params)
                                   (do
                                     (audit/log-participant {:request request
                                                             :target-kv {:k audit/payment
                                                                         :v (:ORDER_NUMBER params)}
                                                             :change {:type audit/create-op
                                                                      :new params}})
                                     (success-redirect url-helper lang (:exam_session_id payment)))
                                   (error-redirect url-helper lang)))
                               (error-redirect url-helper "fi")))))
     (GET "/cancel" request
       (let [params (:params request)]
         (log/info "Received payment cancel params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-return-params? db params)
                               (do
                                 (let [payment (paytrail-payment/get-payment db params)
                                       lang (or (:lang payment) "fi")]
                                   (audit/log-participant {:request request
                                                           :target-kv {:k audit/payment
                                                                       :v (:ORDER_NUMBER params)}
                                                           :change {:type audit/cancel-op
                                                                    :new params}})
                                   (cancel-redirect url-helper lang)))
                               (error-redirect url-helper "fi")))))
     (GET "/notify" {params :params}
       (log/info "Received payment notify params" params)
       (if (paytrail-payment/valid-return-params? db params)
         (if (paytrail-payment/handle-payment-return db email-q url-helper params)
           (ok "OK")
           (internal-server-error "Error in payment notify handling"))
         (internal-server-error "Error in payment notify handling"))))))
