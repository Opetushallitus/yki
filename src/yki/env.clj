(ns yki.env
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [yki.spec :as ys]))

(defn- ->environment [environment]
  {:post [(s/valid? ::ys/environment %)]}
  (keyword environment))

(defmethod ig/init-key ::environment [_ {:keys [environment]}]
  (->environment environment))

(comment
  (-> (local/current-state)
      (ig/find-derived ::environment)))
