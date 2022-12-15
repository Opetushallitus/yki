(ns yki.handler.code
  (:require
    [compojure.api.sweet :refer [context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok]]
    [yki.boundary.codes :as codes]
    [yki.handler.routing :as routing]))

(defmethod ig/init-key :yki.handler/code [_ {:keys [url-helper]}]
  (context (str routing/code-api-root "/:collection") [collection]
    :coercion :spec
    (GET "/" []
      (ok (codes/get-codes url-helper collection)))
    (context "/:code" [code]
      (GET "/" []
        (ok (codes/get-code url-helper collection code))))))
