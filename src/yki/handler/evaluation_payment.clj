(ns yki.handler.evaluation-payment
  (:require [compojure.api.sweet :refer [api context GET]]
            [clojure.tools.logging :as log]
            [yki.handler.routing :as routing]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.util.audit-log :as audit]
            [yki.spec :as ys]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error found conflict]]
            [integrant.core :as ig]))

(defn- success-redirect [url-helper lang order-id]
  (log/info "Evaluation payment success, redirecting to: " (url-helper :evaluation-payment.success-redirect lang order-id))
  (found (url-helper :evaluation-payment.success-redirect lang order-id)))

(defn- error-redirect [url-helper lang order-id]
  (log/info "Evaluation payment error, redirecting to: " (url-helper :evaluation-payment.error-redirect lang order-id))
  (found (url-helper :evaluation-payment.error-redirect lang order-id)))

(defn- cancel-redirect [url-helper lang order-id]
  (log/info "Evaluation payment cancelled, redirecting to: " (url-helper :evaluation-payment.cancel-redirect lang order-id))
  (found (url-helper :evaluation-payment.cancel-redirect lang order-id)))

(defn- evaluation-payment-config [db base-config]
  (let [eval-config (->> db
                         (evaluation-db/get-payment-config)
                         (merge base-config))]
    (if (:test_mode eval-config)
      (assoc eval-config :amount {:READING "1.00" :LISTENING "1.00" :WRITING "1.00" :SPEAKING "1.00"})
      eval-config)))

(defn- handle-exceptions [url-helper f]
  (try
    (f)
    (catch Exception e
      (log/error e "Payment handling failed")
      (error-redirect url-helper))))

(defmethod ig/init-key :yki.handler/evaluation-payment [_ {:keys [db auth access-log payment-config url-helper email-q]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-config) (some? url-helper) (some? email-q)]}
  (api
   (context routing/evaluation-payment-root []
     :coercion :spec
     :no-doc true
     :middleware [auth access-log]
     (GET "/formdata" {session :session}
       :query-params [evaluation-order-id :- ::ys/id {lang :- ::ys/language-code "fi"}]
       :return ::ys/evaluation-payment-form-data
       (if-let [order (evaluation-db/get-evaluation-order-with-payment db evaluation-order-id)]
         (if (= (:state order) "PAID")
           (do
             (log/error "Order" evaluation-order-id "has already been paid")
             (conflict {:error "Order has already been paid"}))
           (if-let [formdata (paytrail-payment/create-evaluation-payment-form-data order (evaluation-payment-config db payment-config) url-helper)]
             (do
               (log/info "Get payment form data success for order " evaluation-order-id)
               (ok formdata))
             (do (log/error "Failed to create evaluation form data from order " order)
                 (internal-server-error {:error "Payment form data creation failed"}))))
         (do (log/error "Could not find evaluation order with id" evaluation-order-id)
             (internal-server-error {:error "Could not find evaluation order"}))))
     (GET "/success" request
       (let [params      (:params request)
             eval-config (evaluation-payment-config db payment-config)]
         (log/info "Received evaluation payment success params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-evaluation-return-params? params eval-config)
                               (let [payment   (paytrail-payment/get-evaluation-payment db params)
                                     lang      (or (:lang payment) "fi")
                                     order-id  (:evaluation_order_id payment)]
                                 (if (paytrail-payment/handle-evaluation-payment-return db email-q url-helper eval-config params)
                                   (do
                                     (audit/log {:request request
                                                 :target-kv {:k audit/evaluation-payment
                                                             :v (:ORDER_NUMBER params)}
                                                 :change {:type audit/create-op
                                                          :new params}})
                                     (success-redirect url-helper lang order-id))
                                   (error-redirect url-helper lang order-id)))
                               (do (log/error "Paytrail return params not valid")
                                   (error-redirect url-helper "fi" nil))))))
     (GET "/cancel" request
       (let [params (:params request)]
         (log/info "Received evaluation payment cancel params" params)
         (handle-exceptions url-helper
                            #(if (paytrail-payment/valid-evaluation-return-params? params (evaluation-payment-config db payment-config))
                               (do
                                 (let [payment (paytrail-payment/get-evaluation-payment db params)
                                       lang (or (:lang payment) "fi")
                                       order-id  (:evaluation_order_id payment)]
                                   (audit/log {:request request
                                               :target-kv {:k audit/evaluation-payment
                                                           :v (:ORDER_NUMBER params)}
                                               :change {:type audit/cancel-op
                                                        :new params}})
                                   (cancel-redirect url-helper lang order-id)))
                               (error-redirect url-helper "fi" nil)))))
     (GET "/notify" {params :params}
       (log/info "Received evaluation payment notify params" params)
       (if (paytrail-payment/valid-evaluation-return-params? params (evaluation-payment-config db payment-config))
         (if (paytrail-payment/handle-evaluation-payment-return db email-q url-helper params)
           (ok "OK")
           (internal-server-error "Error in evaluation payment notify handling"))
         (internal-server-error "Error in evaluation payment notify handling"))))))
