(ns yki.util.http-util
  (:require [org.httpkit.client :as http]))

(defn do-request
  [{:keys [url method] :as opts}]
  (let [opts        (update opts :headers merge {"clientSubSystemCode" "yki" "Caller-Id" "yki"})
        method-name (clojure.string/upper-case (name method))
        start       (System/currentTimeMillis)
        response    @(http/request opts)
        time        (- (System/currentTimeMillis) start)
        status      (:status response 500)]
    response))

(defn do-get
  [url]
  (do-request {:url url :method :get}))

(defn do-post
  [url opts]
  (do-request (assoc opts :url url :method :post)))

(defn do-delete
  [url]
  (do-request {:url url :method :delete}))
