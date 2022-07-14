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
    [ring.util.http-response :refer [ok internal-server-error found unauthorized]]
    [yki.handler.routing :as routing]
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

(defmethod ig/init-key :yki.handler/payment-paytrail [_ {:keys [db]}]
  (api
    (context routing/payment-v2-root []
      :coercion :spec
      :no-doc true
      ; TODO Initialize handler with auth middleware and use for redirect handler!
      :middleware [wrap-params]
      (GET "/redirect" {query-params :query-params}
        (let [registration-id (query-params "registration-id")]
          (log/info "REDIRECT called for registration-id:" registration-id)
          (found "https://pay.paytrail.com/pay/f9a48756-fe91-11ec-bbb2-075fb18c6d6d"))))
    (context routing/paytrail-payment-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params with-request-validation]
      (GET "/success" {query-params :query-params}
        :return ::ys/payment-paytrail-response
        (log/info "SUCCESS callback invoked with query-params:" query-params)
        (ok {:foo "moiccu"}))
      (GET "/error" {query-params :query-params}
        (log/warn "ERROR callback invoked with query-params:" query-params)
        (ok {:rab :oof}))
      ; Report generation callback
      (POST "/report" req
        (let [body         (:body req)
              headers      (:headers req)
              content-type (infer-content-type headers)]
          (log/info "REPORT callback invoked with headers:" headers)
          (store-report! content-type body)
          (ok "report received"))))))

