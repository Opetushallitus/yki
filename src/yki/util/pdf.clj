(ns yki.util.pdf
  (:require [yki.util.template-util :as template-util])
  (:import (com.github.jhonnymertz.wkhtmltopdf.wrapper.configurations WrapperConfig)
           (com.github.jhonnymertz.wkhtmltopdf.wrapper Pdf)))

(def pdf-wrapper-config (WrapperConfig. "wkhtmltopdf"))

(defn template+data->pdf-bytes ^bytes [url-helper template-name language template-data]
  (let [pdf (Pdf. pdf-wrapper-config)]
    (.addPageFromString pdf (template-util/render url-helper template-name language template-data))
    (.getPDF pdf)))
