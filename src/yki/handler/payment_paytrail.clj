(ns yki.handler.payment-paytrail
  (:require
    [clj-time.core :as t]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.file]
    [ring.util.http-response :refer [ok found unauthorized]]
    [yki.boundary.registration-db :as registration-db]
    [yki.handler.routing :as routing]
    [yki.util.payment-helper :refer [create-payment-for-registration! get-payment-amount-for-registration]]
    [yki.util.payments-api :refer [valid-request?]]
    [yki.spec :as ys])
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


(defmethod ig/init-key :yki.handler/payment-paytrail [_ {:keys [auth access-log db payment-helper]}]
  (api
    (context routing/payment-v2-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" {session :session}
        :path-params [id :- ::ys/registration_id]
        :query-params [lang :- ::ys/language-code]
        (let [external-user-id     (get-in session [:identity :external-user-id])
              external-user-id     "local_test@testi.fi"
              registration-details (registration-db/get-registration-data-for-new-payment db id external-user-id)]
          (log/info "REDIRECT called for registration-id:" id)
          (log/info "Got lang:" lang)
          (log/info registration-details)
          (if registration-details
            (let [amount            (get-payment-amount-for-registration payment-helper registration-details)
                  paytrail-response (create-payment-for-registration! payment-helper nil registration-details lang amount)
                  ; TODO store transaction data on db
                  redirect-url      (paytrail-response "href")]
              (log/info "payment-data" paytrail-response)
              (found redirect-url))
            ; TODO Error redirect, see yki.handler.payment/error-redirect
            (found "https://www.google.com")))))
    (context routing/paytrail-payment-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params with-request-validation]
      (GET "/:id/success" {query-params :query-params}
        :path-params [id :- ::ys/registration_id]
        :return ::ys/payment-paytrail-response
        (log/info "SUCCESS callback for registration" id "invoked with query-params:" query-params)
        (ok {:foo "moiccu"}))
      (GET "/:id/error" {query-params :query-params}
        :path-params [id :- ::ys/registration_id]
        (log/warn "ERROR callback for registration" id "invoked with query-params:" query-params)
        (ok {:rab :oof}))
      ; Report generation callback
      (POST "/report" req
        (let [body         (:body req)
              headers      (:headers req)
              content-type (infer-content-type headers)]
          (log/info "REPORT callback invoked with headers:" headers)
          (store-report! content-type body)
          (ok "report received"))))))

