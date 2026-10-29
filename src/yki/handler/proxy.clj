(ns yki.handler.proxy
  (:require
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [ring.util.http-response :refer [forbidden]]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]
    [yki.util.http-util :as http-util]))

(defn- proxy-request [{:keys [endpoint token]} {:keys [request-method uri body-params query-params headers] :as request}]
  (let [oid         (get-in request [:session :identity :oid])
        auth        (when oid {"Authorization"
                               (str oid ":" (registration/sha256-hash (str oid token)))})
        opts        {:method       request-method
                     :url          (str endpoint uri)
                     :body         (json/write-str body-params)
                     ; The browser never receives Java's CSRF cookie, so the proxy supplies a matching cookie and header itself.
                     :headers      (merge auth
                                          (dissoc headers "cookie" "csrf")
                                          {"CSRF"   http-util/csrf-value
                                           "Cookie" (str "CSRF=" http-util/csrf-value)})
                     :query-params query-params}
        response    @(http/request opts)]
    {:status  (:status response)
     :body    (:body response)
     :headers {"content-type" (:content-type (:headers response))}}))

(defmethod ig/init-key :yki.handler/proxy [_ {:keys [auth access-log environment proxy-config]}]
  {:pre [(some? auth) (some? access-log) (s/valid? ::ys/environment environment) (some? proxy-config)]}
  (let [proxy-request (partial proxy-request proxy-config)]
    (api
      (context routing/api-root []
        :coercion (when-not (#{:qa :prod} environment) :spec)
        :middleware [auth access-log with-error-boundary]
        (context "/public" []
          (GET "/education" request
            (proxy-request request))
          (POST "/education/:registration-id" request
            :path-params [registration-id :- ::ys/id]
            (proxy-request request))
          (POST "/integration-assistance/:registration-id" request
            :path-params [registration-id :- ::ys/id]
            ; The Java backend only receives the OID, so strong authentication must be enforced here.
            (if (= "SUOMIFI" (get-in request [:session :auth-method]))
              (proxy-request request)
              (forbidden))))))))
