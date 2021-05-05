(ns yki.util.http-util
  (:require [org.httpkit.client :as http]
            [clojure.string :as string]
            [clojure.tools.logging :refer [info]]))

(defn- remove-ssn [url]
  (string/replace url #"hetu.*?(?=&|\?|$)" "hetu=***********"))

(def csrf-value "yki")

(defn headers-and-cookies-settings [opts]
  (-> opts
      (update :headers merge
              {"Caller-Id" "1.2.246.562.10.00000000001.yki"}
              {"CSRF" csrf-value})
      (update :cookies merge {"CSRF" {:value csrf-value :path "/"}})))

(defn do-request
  [{:keys [url method] :as opts}]

  (let [opts        (headers-and-cookies-settings opts)
        method-name (string/upper-case (name method))
        start       (System/currentTimeMillis)
        _ (info "do-request, opts:" opts)
        response    @(http/request opts)
        time        (- (System/currentTimeMillis) start)
        status      (:status response 500)
        _           (info "Request" method-name (remove-ssn url) "returned" status "in" time "ms")]
    response))

(defn do-get
  [url query-params]
  (do-request {:url url :method :get :query-params query-params}))

(defn do-post
  [url opts]
  (do-request (assoc opts :url url :method :post)))

(defn do-delete
  [url opts]
  (do-request (assoc opts :url url :method :delete)))
