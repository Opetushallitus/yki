(ns yki.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [yki.job.scheduled-tasks]
            [duct.core :as duct]))

(duct/load-hierarchy)

(defn- read-external-config! []
  (if (.exists (io/file "./oph-configuration/config.edn"))
    (do
      (System/setProperty "logback.configurationFile" "./oph-configuration/logback.xml")
      (io/file "./oph-configuration/config.edn"))))

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:duct/daemon
                                         :duct.migrator/ragtime
                                         :duct.scheduler/simple])
        profiles [:duct.profile/prod]]
    (->
        (duct/merge-configs (duct/read-config (duct/resource "yki/config.edn")) (duct/read-config (read-external-config!)))
        (duct/exec-config profiles keys))))
