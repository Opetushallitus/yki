(ns yki.handler.evaluation-payment-new
  (:require
    [compojure.api.sweet :refer [api context GET]]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jeesql.core :refer [require-sql]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [bad-request found ok unauthorized]]
    [yki.handler.routing :as routing]
    [yki.boundary.evaluation-db :as evaluation-db]
    [yki.boundary.localisation :as localisation]
    [yki.middleware.payment :refer [with-request-validation]]
    [yki.registration.email :as registration-email]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit]
    [yki.util.evaluation-payment-helper :refer [order-id->payment-data]]
    [yki.util.paytrail-payments :refer [sign-string]]
    [yki.util.template-util :as template-util]))

(require-sql ["yki/queries.sql" :as q])

(defn- success-redirect [url-helper lang order-id]
  (let [redirect-url (url-helper :yki-ui.evaluation-payment.success-redirect lang order-id)]
    (log/info "Evaluation payment success, redirecting to:" redirect-url)
    (found redirect-url)))

(defn- error-redirect [url-helper lang order-id]
  (let [redirect-url (url-helper :yki-ui.evaluation-payment.error-redirect lang order-id)]
    (log/info "Evaluation payment error, redirecting to:" redirect-url)
    (found redirect-url)))

(defn- cancel-redirect [url-helper lang order-id]
  (let [redirect-url (url-helper :yki-ui.evaluation-payment.cancel-redirect lang order-id)]
    (log/info "Evaluation payment cancelled, redirecting to:" redirect-url)
    (found redirect-url)))

(defn- handle-exceptions [url-helper f lang order-id]
  (try
    (f)
    (catch Exception e
      (log/error e "Payment handling failed")
      (error-redirect url-helper lang order-id))))

(defn send-evaluation-order-completed-emails! [email-q payment-helper pdf-renderer order-data lang]
  (let [order-time    (:created order-data)
        template-data (assoc order-data
                        :subject (str (localisation/get-translation lang "email.evaluation_payment_success.subject") ":")
                        :language (template-util/get-language (:language_code order-data) lang)
                        :level (template-util/get-level (:level_code order-data) lang)
                        :order_time order-time
                        :amount (int (:amount order-data))
                        :order_number (:reference order-data)
                        :receipt_date (:paid_at order-data)
                        :payment_date (:paid_at order-data))]
    (log/info (str "Evaluation payment success, sending email to " (:email order-data) " and Kirjaamo"))
    ;; Customer email
    (registration-email/send-customer-evaluation-registration-completed-email! email-q payment-helper pdf-renderer lang order-time template-data)
    ;; Kirjaamo email
    (registration-email/send-kirjaamo-evaluation-registration-completed-email! email-q lang order-time "kirjaamo@oph.fi" template-data)))

(defn- handle-success [{:keys [db email-q payment-helper pdf-renderer url-helper]} lang request]
  (let [{transaction-id "checkout-transaction-id"
         amount         "checkout-amount"
         payment-status "checkout-status"} (:query-params request)
        payment-details     (evaluation-db/get-new-payment-by-transaction-id db transaction-id)
        evaluation-order-id (:evaluation_order_id payment-details)]
    (if (and payment-details
             (= (* 100 (int (:amount payment-details)))
                (Integer/parseInt amount))
             (= "ok" payment-status))
      (let [payment-id      (:id payment-details)
            order-data      (first (q/select-evaluation-order-with-subtests-by-order-id (:spec db) {:evaluation_order_id evaluation-order-id}))
            updated-payment (evaluation-db/complete-new-payment! db payment-id)
            handle-payment  #(do
                               (audit/log {:request   request
                                           :target-kv {:k audit/evaluation-payment
                                                       :v (:reference payment-details)}
                                           :change    {:type audit/create-op
                                                       :new  (:params request)}})
                               ; Paytrail may call success endpoint multiple times.
                               ; Only send email on the first invocation.
                               ; Always redirect to payment success page, as otherwise the user
                               ; might be redirected to an error page even though the payment went through!
                               (when updated-payment
                                 (send-evaluation-order-completed-emails! email-q payment-helper pdf-renderer (merge updated-payment order-data) lang))
                               (success-redirect url-helper lang evaluation-order-id))]
        (handle-exceptions url-helper handle-payment lang evaluation-order-id))
      (do
        (log/error "Success callback invoked with unexpected parameters for transaction-id" transaction-id
                   "corresponding to evaluation order with id" evaluation-order-id
                   "; amount:" amount "; payment-status:" payment-status)
        (error-redirect url-helper lang nil)))))

(defn- handle-error [{:keys [db url-helper]} request lang]
  (let [{transaction-id "checkout-transaction-id"
         payment-status "checkout-status"} (:query-params request)
        payment-details (evaluation-db/get-new-payment-by-transaction-id db transaction-id)]
    (log/info "Error callback invoked for transaction-id" transaction-id "with payment-status" payment-status)
    (if-let [evaluation-order-id (:evaluation_order_id payment-details)]
      (cancel-redirect url-helper lang evaluation-order-id)
      (error-redirect url-helper lang nil))))

(defmethod ig/init-key :yki.handler/evaluation-payment-new
  [_ {:keys [db auth access-log payment-helper pdf-renderer url-helper email-q]
      :as   ctx}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-helper) (some? url-helper) (some? pdf-renderer) (some? email-q)]}
  (api
    (context routing/evaluation-payment-new-paytrail-callback-root []
      ; TODO Can this whole (context ...) block be removed?
      ; Was probably only used for the old yki-frontend.
      ; After refactoring is now identical to the newer context block.
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (handle-success ctx lang request))
      (GET "/:lang/error" request
        :path-params [lang :- ::ys/language-code]
        (handle-error ctx request lang)))
    (context routing/evaluation-payment-for-new-ui-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (handle-success ctx lang request))
      (GET "/:lang/error" request
        :path-params [lang :- ::ys/language-code]
        (handle-error ctx request lang)))))
