(ns yki.util.pdf-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clj-time.core :as t]
    [integrant.core :as ig]
    [stub-http.core :refer [with-routes!]]
    [yki.util.pdf :refer [template+data->pdf-bytes]]))

(deftest pdf-renderer-test
  (with-routes!
    {}
    (testing "Ensure PdfTemplateRenderer renders PDF content given a template and data"
      (let [pdf-renderer (ig/init-key :yki.util/pdf {})
            pdf-bytes    (template+data->pdf-bytes
                           pdf-renderer
                           "receipt_base"
                           "fi"
                           {:receipt_id   "YKI_TEST_ID"
                            :receipt_date (t/now)
                            :payment_date (t/now)})]
        (is (<= 10000 (alength pdf-bytes) 20000))))))
