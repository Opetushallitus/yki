(ns yki.util.pdf-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clj-time.core :as t]
    [integrant.core :as ig]
    [stub-http.core :refer [with-routes!]]
    [yki.handler.base-test :as base]
    [yki.util.pdf :refer [template+data->pdf-bytes]]))

(deftest pdf-renderer-test
  (with-routes! {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                                           :body   (slurp "test/resources/localisation.json")}})
  (testing "Ensure PdfTemplateRenderer renders PDF content given a template and data"
    (let [url-helper   (base/create-url-helper "localhost")
          pdf-renderer (ig/init-key :yki.util/pdf {:url-helper url-helper})
          pdf-bytes    (template+data->pdf-bytes
                         pdf-renderer
                         "receipt_base"
                         "fi"
                         {:receipt_id   "YKI_TEST_ID"
                          :receipt_date (t/now)
                          :payment_date (t/now)})]
      (is (<= 10000 (alength pdf-bytes) 20000)))))
