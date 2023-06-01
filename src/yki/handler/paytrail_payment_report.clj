(ns yki.handler.paytrail-payment-report
  (:require
    [clj-time.core :as t]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok]]
    [yki.handler.routing :as routing]
    [yki.middleware.payment :refer [with-request-validation]]
    [clojure.data.csv :as csv]
    [clojure.data.json :as json])
  (:import (java.io FileOutputStream InputStream)))

(defn- infer-content-type [headers]
  (let [content-type-string (headers "content-type")]
    (when content-type-string
      (cond
        (str/starts-with? content-type-string "text/csv")
        :csv
        (str/starts-with? content-type-string "application/json")
        :json))))

(defn report-file-path ^String [content-type]
  (case content-type
    :csv
    (str "/tmp/yki/report/csv/" (t/now) ".csv")
    :json
    (str "/tmp/yki/report/json/" (t/now) ".json")))

(defn- store-report! [content-type report-contents]
  (let [path-to-report (report-file-path content-type)]
    (jio/make-parents path-to-report)
    (with-open [fos (FileOutputStream. path-to-report)]
      (if (instance? InputStream report-contents)
        (.transferTo ^InputStream report-contents fos)
        ; Else report-contents is likely a map or string
        ; -> try to just spit it.
        (spit fos report-contents)))))

(defn content->records [content-type report-contents]
  (log/info "Trying to interpret records of type" content-type "with report-contents of class" (class report-contents))
  (case content-type
    :csv
    (let [[header & rows] (csv/read-csv report-contents :separator \;)]
      (log/info "CSV payload contains the following fields:" (str/join " | " header))
      (map zipmap (repeat header) rows))
    :json
    (json/read-str report-contents)))

(defmethod ig/init-key :yki.handler/paytrail-payment-report [_ {:keys [auth payment-helper]}]
  {:pre [(some? auth) (some? payment-helper)]}
  (api
    (context routing/paytrail-payment-report-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      ; Handler for writing requested payment report to disk
      (POST "/store" req
        (let [body         (:body req)
              headers      (:headers req)
              content-type (infer-content-type headers)]
          (log/info "Storing Paytrail payments report")
          (store-report! content-type body)
          (ok {})))
      ; Handler for logging refunded payments
      (POST "/log-refunds" req
        (log/info "Logging refunded Paytrail payments")
        (let [body            (:body req)
              headers         (:headers req)
              content-type    (infer-content-type headers)
              payment-records (content->records content-type body)]
          (doseq [record payment-records]
            (when-not (str/blank? (record "Refund"))
              (log/info "Found refunded payment!"
                        (select-keys
                          record
                          ["Entry date" "Time" "Amount" "Status"
                           "Transaction ID" "Reference" "Paytrail reference"
                           "Refund"]))))
          (ok {}))))))
