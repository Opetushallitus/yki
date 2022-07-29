(ns yki.handler.payment-paytrail
  (:require
    [clj-time.core :as t]
    [clojure.java.io :as jio]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.file]
    [ring.util.http-response :refer [bad-request ok found unauthorized]]
    [yki.boundary.registration-db :as registration-db]
    [yki.handler.routing :as routing]
    [yki.spec :as ys]
    [yki.registration.email :as registration-email]
    [yki.util.db :refer [rollback-on-exception]]
    [yki.util.payment-helper :refer [create-payment-for-registration! get-payment-amount-for-registration]]
    [yki.util.payments-api :refer [valid-request?]])
  (:import (java.io FileOutputStream InputStream)))

(defn- infer-content-type [headers]
  (let [content-type-string (headers "content-type")]
    (when content-type-string
      (cond
        (str/starts-with? content-type-string "text/csv")
        :csv
        (str/starts-with? content-type-string "application/json")
        :edn))))

(defn report-file-path ^String [content-type]
  (case content-type
    :csv
    (str "/tmp/yki/report/csv/" (t/now) ".csv")
    :edn
    (str "/tmp/yki/report/edn/" (t/now) ".edn")))

(defn- store-report! [content-type report-contents]
  (let [path-to-report (report-file-path content-type)]
    (jio/make-parents path-to-report)
    (with-open [fos (FileOutputStream. path-to-report)]
      (if (instance? InputStream report-contents)
        (.transferTo ^InputStream report-contents fos)
        ; Else report-contents is likely a map or string
        ; -> try to just spit it.
        (spit fos report-contents)))))

(defn- with-request-validation [handler]
  (fn
    ([request]
     (if-let [{body :body} (valid-request? request)]
       (handler (assoc request :body body))
       (unauthorized {:reason "Signature is not valid for request!"})))))

(defn- registration-redirect [db payment-helper url-helper lang registration]
  (case (:state registration)
    "COMPLETED"
    (url-helper :payment.success-redirect lang (:exam_session_id registration))
    "SUBMITTED"
    (jdbc/with-db-transaction [tx (:spec db)]
      (rollback-on-exception
        tx
        #(let [amount            (:paytrail (get-payment-amount-for-registration payment-helper registration))
               paytrail-response (create-payment-for-registration! payment-helper tx registration lang amount)
               redirect-url      (paytrail-response "href")]
           redirect-url)))
    ; Other values are unexpected. Redirect to error page.
    (url-helper :payment.error-redirect lang (:exam_session_id registration))))

(defmethod ig/init-key :yki.handler/payment-paytrail [_ {:keys [auth access-log db payment-helper url-helper email-q]}]
  {:pre [(some? auth) (some? access-log) (some? db) (some? email-q) (some? payment-helper) (some? url-helper)]}
  (api
    (context routing/payment-v2-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" {session :session}
        :path-params [id :- ::ys/registration_id]
        :query-params [lang :- ::ys/language-code]
        (let [external-user-id     (get-in session [:identity :external-user-id])
              ;external-user-id     "local_test@testi.fi"
              registration-details (registration-db/get-registration-data-for-new-payment db id external-user-id)]
          (if registration-details
            ; Registration details found, redirect based on registration state.
            (ok {:redirect (registration-redirect db payment-helper url-helper lang registration-details)})
            ; No registration matching given id and external-user-id from session.
            ; TODO Generic error redirect?
            (bad-request {:reason :registration-not-found})))))
    (context routing/paytrail-payment-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params with-request-validation]
      (GET "/:lang/success" {query-params :query-params}
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               amount         "checkout-amount"
               payment-status "checkout-status"} query-params
              payment-details     (registration-db/get-new-payment-details db transaction-id)
              registration-id     (:registration_id payment-details)
              participant-details (registration-db/get-participant-data-by-registration-id db registration-id)]
          ; TODO Reconsider if it's necessary to validate payment details below.
          (if (and payment-details
                   (= (int (:amount payment-details))
                      (Integer/parseInt amount))
                   (= "ok" payment-status))
            (let [payment-id                        (:id payment-details)
                  send-registration-complete-email! #(registration-email/send-exam-registration-completed-email!
                                                       email-q
                                                       url-helper
                                                       lang
                                                       participant-details)]
              (if (registration-db/complete-new-payment-and-exam-registration! db registration-id payment-id send-registration-complete-email!)
                (log/info "Completed payment with transaction-id" transaction-id ", updating registration with id" registration-id "to COMPLETED.")
                (log/info "Success callback invoked for transaction-id" transaction-id "corresponding to already completed registration with id" registration-id "; this is a no-op."))
              (found (url-helper :payment.success-redirect lang (:exam_session_id payment-details))))
            (do
              (log/error "Success callback invoked with unexpected parameters for transaction-id" transaction-id "corresponding to registration with id" registration-id
                         "; amount:" amount "; payment-status:" payment-status)
              (bad-request {:reason "Payment details didn't match expectation."})))))
      (GET "/:lang/error" {query-params :query-params}
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               payment-status "checkout-status"} query-params]
          (log/info "Error callback invoked for transaction-id" transaction-id "with payment-status" payment-status)
          (ok {})))
      ; Report generation callback
      (POST "/report" req
        (let [body         (:body req)
              headers      (:headers req)
              content-type (infer-content-type headers)]
          (log/info "REPORT callback invoked with headers:" headers)
          (store-report! content-type body)
          (ok {}))))))

