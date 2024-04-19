(ns yki.handler.debug
  (:require
    [clojure.data.csv :as csv]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [internal-server-error ok]]
    [yki.boundary.debug :as b]
    [yki.boundary.onr :as onr]
    [yki.handler.routing :as routing])
  (:import (java.io StringWriter)))

(defmethod ig/init-key :yki.handler/debug [_ {:keys [access-log auth db onr-client url-helper]}]
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
        (let [participant-onr-data (b/get-participant-onr-data db)
              columns              [:oid :oppijanumero :participant_id :is_individualized :modified]
              batch-size           1000]
          (with-open [writer (StringWriter.)]
            (csv/write-csv writer [(map name columns)] :separator \;)
            (doseq [batch (->> participant-onr-data
                               (map (apply juxt columns))
                               (partition-all batch-size))]
              (csv/write-csv writer batch :separator \;))
            ; TODO Add link to ONR in the data!
            (-> (.toString writer)
                (ok)
                (assoc-in [:headers "Content-Type"] "text/csv")))))
      (POST "/participants/sync-onr-data" _
        ; TODO Syncing data may take a long time, so this endpoint should instead trigger a background job and return instantly.
        (try
          (let [participants-to-sync (b/get-participants-for-onr-check db)
                batch-size           100]
            (doseq [participants-batch (partition-all batch-size participants-to-sync)]
              (let [oid->participant (into {} (map (juxt :person_oid identity)) participants-batch)
                    onr-data         (->> participants-batch
                                          (map :person_oid)
                                          (onr/list-persons-by-oids onr-client))]
                (doseq [onr-entry onr-data]
                  (let [onr-details {:person_oid        (onr-entry "oidHenkilo")
                                     :oppijanumero      (onr-entry "oppijanumero")
                                     :is_individualized (or (onr-entry "yksiloity")
                                                            (onr-entry "yksiloityVTJ"))}
                        oid         (:person_oid onr-details)]
                    (b/upsert-participant-onr-data!
                      db
                      (merge
                        (oid->participant oid)
                        onr-details)))))))
          (ok {:success true})
          (catch Exception e
            (log/error e "Error syncing onr data!")
            (internal-server-error {:error true})))))))
