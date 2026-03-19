(ns yki.handler.proxy
  (:require
    [clojure.spec.alpha :as s]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [yki.boundary.yki-v2 :as yki-v2]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/proxy [_ {:keys [auth access-log environment proxy-config]}]
  {:pre [(some? auth) (some? access-log) (s/valid? ::ys/environment environment) (some? proxy-config)]}
  (let [proxy-request (partial yki-v2/proxy-request proxy-config)]
    (api
      (context routing/api-root []
        :coercion (when-not (#{:qa :prod} environment) :spec)
        :middleware [auth access-log with-error-boundary]
        (context "/public" []
          (GET "/education" request
            (proxy-request request))
          (POST "/education/:registration-id" request
            :path-params [registration-id :- ::ys/id]
            (proxy-request request)))))))
