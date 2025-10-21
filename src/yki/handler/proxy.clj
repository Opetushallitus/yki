(ns yki.handler.proxy
  (:require
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.tools.logging :refer [info]]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]))

(defn- proxy-request [{:keys [endpoint token]} {:keys [request-method uri body-params headers] :as request}]
  (let [oid         (get-in request [:session :identity :oid])
        auth        (when oid {"Authorization"
                               (str oid ":" (registration/sha256-hash (str oid token)))})
        opts        {:method  request-method
                     :url     (str endpoint uri)
                     :body    (json/write-str body-params)
                     :headers (merge auth headers)}
        method-name (string/upper-case (name request-method))
        start       (System/currentTimeMillis)
        response    @(http/request opts)
        time        (- (System/currentTimeMillis) start)
        status      (:status response 500)
        _           (info "Request" method-name uri "returned" status "in" time "ms")]
    {:status (:status response)
     :body   (:body response)}))

(defmethod ig/init-key :yki.handler/proxy [_ {:keys [auth access-log environment proxy-config]}]
  {:pre [(some? auth) (some? access-log) (s/valid? ::ys/environment environment) (some? proxy-config)]}
  (let [proxy-request (partial proxy-request proxy-config)]
    (api
      (context routing/api-root []
        :coercion (when-not (#{:qa :prod} environment) :spec)
        :middleware [auth access-log with-error-boundary]
        (context "/public" []
          (POST "/education/:registration-id" request
            :path-params [registration-id :- ::ys/id]
            (proxy-request request))
          (GET "/uploadPostPolicy/:exam-event-id" request
            :path-params [exam-event-id :- ::ys/id]
            (proxy-request request)))))))
