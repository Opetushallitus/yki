(ns yki.handler.debug
  (:require
    [clojure.data.csv :as csv]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok]]
    [yki.boundary.debug :as b]
    [yki.handler.routing :as routing])
  (:import (java.io StringWriter)))

(defn- with-onr-url [url-helper {:keys [oid] :as data}]
  (assoc data :onr_url (url-helper :henkilo-ui.henkilo-by-oid oid)))

(defmethod ig/init-key :yki.handler/debug [_ {:keys [access-log auth db url-helper]}]
  {:pre [(some? access-log)
         (some? auth)
         (some? db)
         (some? url-helper)]}
  (api
    (context routing/debug-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/participants/onr" _
        ;:query-params [individualized :- boolean?]
        (let [participant-onr-data (->> (b/get-participant-onr-data db)
                                        (map #(with-onr-url url-helper %)))
              columns              [:oid :oppijanumero :onr_url :participant_id :is_individualized :modified]
              batch-size           1000]
          (with-open [writer (StringWriter.)]
            (csv/write-csv writer [(map name columns)] :separator \;)
            (doseq [batch (->> participant-onr-data
                               (map (apply juxt columns))
                               (partition-all batch-size))]
              (csv/write-csv writer batch :separator \;))
            (-> (.toString writer)
                (ok)
                (assoc-in [:headers "Content-Type"] "text/csv"))))))))
