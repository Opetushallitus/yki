(ns yki.handler.code
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.codes :as codes]
            [ring.util.http-response :refer [ok]]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/code [_ {:keys [url-helper]}]
  (context routing/code-api-root []
    :coercion :spec
    (GET "/:code" [code]
      (ok (codes/get-code url-helper code)))))
