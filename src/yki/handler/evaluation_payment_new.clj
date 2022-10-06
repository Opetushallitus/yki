(ns yki.handler.evaluation-payment-new
  (:require
    [compojure.api.sweet :refer [api context GET]]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jeesql.core :refer [require-sql]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [bad-request ok unauthorized]]
    [yki.handler.evaluation-payment :refer [cancel-redirect error-redirect success-redirect handle-exceptions]]
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

(defn send-evaluation-order-completed-emails! [email-q url-helper order-data lang]
  (let [order-time    (:created order-data)
        template-data (assoc order-data
                        :subject (str (localisation/get-translation url-helper (str "email.evaluation_payment.subject") lang) ":")
                        :language (template-util/get-language url-helper (:language_code order-data) lang)
                        :level (template-util/get-level url-helper (:level_code order-data) lang)
                        :subtests (template-util/get-subtests url-helper (:subtests order-data) lang)
                        :order_time order-time
                        :amount (int (:amount order-data))
                        :order_number (:reference order-data))]
    (log/info (str "Evaluation payment success, sending email to " (:email order-data) " and Kirjaamo"))
    ;; Customer email
    (registration-email/send-customer-evaluation-registration-completed-email! email-q url-helper lang order-time template-data)
    ;; Kirjaamo email
    (registration-email/send-kirjaamo-evaluation-registration-completed-email! email-q url-helper lang order-time "kirjaamo@oph.fi" template-data)))

(defmethod ig/init-key :yki.handler/evaluation-payment-new [_ {:keys [db auth access-log payment-helper url-helper email-q]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-helper) (some? url-helper) (some? email-q)]}
  (api
    (context routing/evaluation-payment-new-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" _
        :path-params [id :- ::ys/registration_id]
        :query-params [signature :- ::ys/non-blank-string]
        (if (= signature (sign-string (:payment-config payment-helper) (str id)))
          (if-let [{state :state
                    href  :href} (order-id->payment-data payment-helper id)]
            (if (= "UNPAID" state)
              (ok {:redirect href})
              (do (log/error "Payment not in unpaid state. Order id:" id "state:" state)
                  (bad-request {:error "Payment not unpaid."})))
            (do (log/error "Could not find payment data corresponding to evaluation order with id" id)
                (bad-request {:error "Could not find payment data for order"})))
          (unauthorized {:reason "Signature is not valid for request!"}))))
    (context routing/evaluation-payment-new-paytrail-callback-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               amount         "checkout-amount"
               payment-status "checkout-status"} (:query-params request)
              payment-details     (evaluation-db/get-new-payment-by-transaction-id db transaction-id)
              evaluation-order-id (:evaluation_order_id payment-details)]
          (if (and payment-details
                   (= (* 100 (int (:amount payment-details)))
                      (Integer/parseInt amount))
                   (= "ok" payment-status))
            (let [payment-id     (:id payment-details)
                  order-data     (first (q/select-evaluation-order-with-subtests-by-order-id (:spec db) {:evaluation_order_id evaluation-order-id}))
                  template-data  (merge payment-details order-data)
                  updated?       (evaluation-db/complete-new-payment! db payment-id)
                  handle-payment #(do
                                    (audit/log {:request   request
                                                :target-kv {:k audit/evaluation-payment
                                                            :v (:reference payment-details)}
                                                :change    {:type audit/create-op
                                                            :new  (:params request)}})
                                    ; Paytrail may call success endpoint multiple times.
                                    ; Only send email on the first invocation.
                                    ; Always redirect to payment success page, as otherwise the user
                                    ; might be redirected to an error page even though the payment went through!
                                    (when updated?
                                      (send-evaluation-order-completed-emails! email-q url-helper template-data lang))
                                    (success-redirect url-helper lang evaluation-order-id))]
              (handle-exceptions url-helper handle-payment lang evaluation-order-id))
            (do
              (log/error "Success callback invoked with unexpected parameters for transaction-id" transaction-id
                         "corresponding to evaluation order with id" evaluation-order-id
                         "; amount:" amount "; payment-status:" payment-status)
              (error-redirect url-helper lang nil)))))
      (GET "/:lang/error" {query-params :query-params}
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               payment-status "checkout-status"} query-params
              payment-details (evaluation-db/get-new-payment-by-transaction-id db transaction-id)]
          (log/info "Error callback invoked for transaction-id" transaction-id "with payment-status" payment-status)
          (if-let [evaluation-order-id (:evaluation_order_id payment-details)]
            (cancel-redirect url-helper lang evaluation-order-id)
            (error-redirect url-helper lang nil)))))))