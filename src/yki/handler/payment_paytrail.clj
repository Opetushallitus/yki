(ns yki.handler.payment-paytrail
  (:require
    [clojure.java.io :as jio]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.file]
    [ring.util.http-response :refer [ok internal-server-error found]]
    [yki.handler.routing :as routing]
    [yki.spec :as ys])
  (:import (java.io FileOutputStream)))

(defmethod ig/init-key :yki.handler/payment-paytrail [_ {:keys [db]}]
  (api
    (context routing/paytrail-payment-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params]
      (GET "/success" {query-params :query-params}
        :return ::ys/payment-paytrail-response
        (log/info "SUCCESS callback invoked with query-params:" query-params)
        (ok {:foo "moiccu"}))
      (GET "/error" {query-params :query-params}
        (log/warn "ERROR callback invoked with query-params:" query-params)
        (ok {:rab :oof}))
      ; Report generation callback
      (POST "/report" req
        (let [body-params (:body-params req)
              headers     (:headers req)]
          (log/info "REPORT callback invoked with headers:" headers)
          (log/info "REPORT callback invoked with body:" body-params)
          (let [output-file "/tmp/yki/report.edn"]
            (jio/make-parents output-file)
            (with-open [fos (FileOutputStream. output-file)]
              (spit fos body-params)))
          (ok "report received"))))))

