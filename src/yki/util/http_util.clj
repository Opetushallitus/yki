(ns yki.util.http-util
  (:require [org.httpkit.client :as http]
            [clojure.string :as string]
            [clojure.tools.logging :refer [info]]))

(defn do-request
  [{:keys [url method] :as opts}]

  (let [opts        (update opts :headers merge {"clientSubSystemCode" "yki" "Caller-Id" "yki"})
        method-name (string/upper-case (name method))
        start       (System/currentTimeMillis)
        response    @(http/request opts)
        time        (- (System/currentTimeMillis) start)
        status      (:status response 500)
        _           (info "Request" method-name url "returned" status "in" time "ms")]
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
