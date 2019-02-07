(ns yki.handler.code
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.codes :as codes]
            [ring.util.http-response :refer [ok]]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/code [_ {:keys [url-helper]}]
  (context (str routing/code-api-root "/:collection") [collection]
    :coercion :spec
    (GET "/" []
      (ok (codes/get-codes url-helper collection)))
    (context "/:code" [code]
      (GET "/" []
        (ok (codes/get-code url-helper collection code))))))
