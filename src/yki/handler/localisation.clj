(ns yki.handler.localisation
  (:require [compojure.api.sweet :refer [context GET]]
            [yki.handler.routing :as routing]
            [yki.boundary.localisation :as localisation]
            [ring.util.response :refer [response]]
            [yki.spec :as ys]
            [ring.util.request]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/localisation [_ {:keys [url-helper access-log]}]
  (context routing/localisation-api-root []
    :coercion :spec
    :middleware [access-log]
    :query-params [{category :- ::ys/category "yki"}
                   {key :- ::ys/key nil}
                   {lang :- ::ys/lang "fi"}]
    (GET "/" []
      (response (localisation/get-translations url-helper category key lang)))))
