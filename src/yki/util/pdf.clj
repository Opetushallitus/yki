(ns yki.util.pdf
  (:require
    [integrant.core :as ig]
    [yki.util.template-util :as template-util])
  (:import (com.github.jhonnymertz.wkhtmltopdf.wrapper.configurations WrapperConfig)
           (com.github.jhonnymertz.wkhtmltopdf.wrapper Pdf)))

(def pdf-wrapper-config (WrapperConfig. "wkhtmltopdf"))

(defprotocol PdfTemplateRenderer
  (template+data->pdf-bytes [_ template-name language template-data]))

(defrecord PdfTemplateRendererImpl
  [url-helper]
  PdfTemplateRenderer
  (template+data->pdf-bytes [_ template-name language template-data]
    (let [pdf (Pdf. pdf-wrapper-config)]
      (.addPageFromString pdf (template-util/render url-helper template-name language template-data))
      (.getPDF pdf))))

(defmethod ig/init-key :yki.util/pdf [_ {:keys [url-helper]}]
  (->PdfTemplateRendererImpl url-helper))
