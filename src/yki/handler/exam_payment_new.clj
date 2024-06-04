(ns yki.handler.exam-payment-new
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [bad-request found internal-server-error ok]]
    [yki.boundary.registration-db :as registration-db]
    [yki.boundary.exam-payment-new-db :as payment-db]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.organization :as organization]
    [yki.handler.routing :as routing]
    [yki.middleware.payment :refer [with-request-validation]]
    [yki.spec :as ys]
    [yki.registration.email :as registration-email]
    [yki.util.db :refer [rollback-on-exception]]
    [yki.util.exam-payment-helper :refer [registration->payment get-payment-amount-for-registration]]
    [yki.util.audit-log :as audit]
    [yki.util.common :refer [format-datetime-for-export]]
    [yki.util.template-util :as template-util])
  (:import
    (java.time LocalDate)))

(defn- registration-redirect [db payment-helper url-helper lang registration]
  (case (:state registration)
    "COMPLETED"
    (url-helper :yki-ui.registration.payment-success.url (:exam_session_id registration))
    "SUBMITTED"
    (jdbc/with-db-transaction [tx (:spec db)]
      (rollback-on-exception
        tx
        #(let [amount            (:paytrail (get-payment-amount-for-registration payment-helper registration))
               paytrail-response (registration->payment payment-helper tx registration lang amount)
               redirect-url      (paytrail-response "href")]
           redirect-url)))
    ; Other values are unexpected. Redirect to error page.
    (url-helper :yki-ui.registration.payment-error.url (:exam_session_id registration))))

(defn- payment->json [{:keys [amount exam_date form language_code level_code organizer_name paid_at reference original_exam_date]}]
  {:organizer          organizer_name
   :paid_at            (format-datetime-for-export paid_at)
   :exam_date          exam_date
   :exam_language      (template-util/get-language language_code "fi")
   :exam_level         (template-util/get-level level_code "fi")
   :original_exam_date original_exam_date
   :last_name          (:last_name form)
   :first_name         (:first_name form)
   :email              (:email form)
   :amount             (->>
                         (/ amount 100)
                         (double)
                         (format "%.2f"))
   :reference          reference})

(defn- with-organizer-names [url-helper payments]
  (when (seq payments)
    (let [organizer-oids      (->> payments
                                   (map :oid)
                                   (distinct))
          oid->organizer-name (->> (organization/get-organizations-by-oids url-helper organizer-oids)
                                   (map (fn [org-data]
                                          [(get org-data "oid")
                                           (get-in org-data ["nimi" "fi"])]))
                                   (into {}))
          with-organizer-name (fn [{:keys [oid] :as val}]
                                (assoc val :organizer_name (oid->organizer-name oid)))]
      (map with-organizer-name payments))))

(defn- success-redirect [url-helper exam-session-id]
  (found (url-helper :yki-ui.registration.payment-success.url exam-session-id)))

(defn- error-redirect [url-helper exam-session-id]
  (found (url-helper :yki-ui.registration.payment-error.url exam-session-id)))

(defn- cancel-redirect [url-helper exam-session-id]
  (found (url-helper :yki-ui.registration.payment-cancel.url exam-session-id)))

(defn- handle-success-callback [db email-q pdf-renderer url-helper lang request]
  (let [{transaction-id "checkout-transaction-id"
         amount         "checkout-amount"
         payment-status "checkout-status"} (:query-params request)
        payment-details     (registration-db/get-new-payment-details db transaction-id)
        registration-id     (:registration_id payment-details)
        participant-details (registration-db/get-participant-data-by-registration-id db registration-id)
        exam-session-id     (:exam_session_id payment-details)]
    (if (and payment-details
             (= (int (:amount payment-details))
                (Integer/parseInt amount))
             (= "ok" payment-status))
      (let [payment-id                        (:id payment-details)
            exam-session-contact-info         (exam-session-db/get-contact-info-by-exam-session-id db exam-session-id)
            exam-session-extra-information    (exam-session-db/get-exam-session-location-extra-information db exam-session-id lang)
            email-template-data               (assoc participant-details
                                                :contact_info exam-session-contact-info
                                                :extra_information (:extra_information exam-session-extra-information))
            send-registration-complete-email! (fn [updated-payment-details]
                                                (registration-email/send-exam-registration-completed-email!
                                                  email-q
                                                  pdf-renderer
                                                  lang
                                                  email-template-data
                                                  updated-payment-details))]
        (if-let [updated-registration (registration-db/complete-new-payment-and-exam-registration! db registration-id payment-id send-registration-complete-email!)]
          (do
            (audit/log-participant {:request   request
                                    :target-kv {:k audit/payment
                                                :v (:reference payment-details)}
                                    :change    {:type audit/create-op
                                                :new  (:params request)}})
            (case (:state updated-registration)
              "COMPLETED" (log/info "Completed payment with transaction-id" transaction-id ", updated registration with id" registration-id "to COMPLETED.")
              "PAID_AND_CANCELLED" (log/warn "Completed payment with transaction-id" transaction-id ", updated registration with id" registration-id "to PAID_AND_CANCELLED")
              (log/error "Completed payment with transaction-id" transaction-id ", but unexpectedly registration with id" registration-id "is in state" (:state updated-registration))))
          (log/info "Success callback invoked for transaction-id" transaction-id "corresponding to already completed registration with id" registration-id "; this is a no-op."))
        (success-redirect url-helper exam-session-id))
      (do
        (log/error "Success callback invoked with unexpected parameters for transaction-id" transaction-id "corresponding to registration with id" registration-id
                   "; amount:" amount "; payment-status:" payment-status)
        (error-redirect url-helper exam-session-id)))))

(defn handle-error-callback [db url-helper request]
  (let [{transaction-id "checkout-transaction-id"
         payment-status "checkout-status"} (:query-params request)
        payment-details (registration-db/get-new-payment-details db transaction-id)]
    (log/info "Error callback invoked for transaction-id" transaction-id
              "with payment-status" payment-status
              "while stored payment-status was" (:state payment-details))
    (when (= "UNPAID" (:state payment-details))
      (payment-db/mark-payment-as-cancelled! db (:id payment-details)))
    (if-let [exam-session-id (:exam_session_id payment-details)]
      (cancel-redirect url-helper exam-session-id)
      (error-redirect url-helper nil))))

(defn redirect-to-paytrail [db payment-helper url-helper lang session id]
  (let [external-user-id     (get-in session [:identity :external-user-id])
        ;external-user-id     "local_test@testi.fi"
        registration-details (registration-db/get-registration-data-for-new-payment db id external-user-id)]
    (if registration-details
      ; Registration details found, redirect based on registration state.
      (found (registration-redirect db payment-helper url-helper lang registration-details))
      ; No registration matching given id and external-user-id from session.
      (bad-request {:reason :registration-not-found}))))

(defmethod ig/init-key :yki.handler/exam-payment-new [_ {:keys [auth access-log db payment-helper pdf-renderer url-helper email-q]}]
  {:pre [(some? auth) (some? access-log) (some? db) (some? email-q) (some? payment-helper) (some? pdf-renderer) (some? url-helper)]}
  (api
    (context routing/payment-v2-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/report" _
        :query-params [from :- ::ys/date-type
                       to :- ::ys/date-type]
        ; Endpoint called from the payment reports view of old yki-frontend.
        (try
          (let [from-inclusive     (LocalDate/parse from)
                to-exclusive       (-> (LocalDate/parse to)
                                       (.plusDays 1))
                completed-payments (payment-db/get-completed-payments-for-timerange db from-inclusive to-exclusive)
                result             (->> completed-payments
                                        (with-organizer-names url-helper)
                                        (map payment->json)
                                        (sort-by (juxt :organizer :last_name :first_name)))]
            (ok {:payments result}))
          (catch Exception e
            (log/error e "Failed to generate payment report from" from "to" to)
            (when-let [response (:response (ex-data e))]
              (log/error "Response status code:" (:status response))
              (log/error "Response body:" (:body response)))
            (internal-server-error {})))))
    (context routing/payment-v3-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" {session :session}
        :path-params [id :- ::ys/registration_id]
        :query-params [lang :- ::ys/language-code]
        (-> (redirect-to-paytrail db payment-helper url-helper lang session id)
            (assoc :session nil))))
    (context routing/paytrail-payment-v2-root []
      ; TODO Is it safe to delete this whole endpoint?
      ; After removing support for redirecting to old UI, this is now identical to ...-v3-root
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (handle-success-callback db email-q pdf-renderer url-helper lang request))
      (GET "/:lang/error" request
        :path-params [lang :- ::ys/language-code]
        (handle-error-callback db url-helper request)))
    (context routing/paytrail-payment-v3-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (handle-success-callback db email-q pdf-renderer url-helper lang request))
      (GET "/:lang/error" request
        :path-params [lang :- ::ys/language-code]
        (handle-error-callback db url-helper request)))))
