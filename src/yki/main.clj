(ns yki.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [yki.job.scheduled-tasks]
            [duct.core :as duct]))

(duct/load-hierarchy)

(defn- read-external-config! []
  (if (.exists (io/file "./oph-configuration/config.edn"))
    (do
      (System/setProperty "clojure.tools.logging.factory" "clojure.tools.logging.impl/slf4j-factory")
      (System/setProperty "logback.configurationFile" "./oph-configuration/logback.xml")
      (io/file "./oph-configuration/config.edn"))))

; (defn migrator-config [migrations]
;   {:duct.migrator/ragtime {:migrations (vec migrations)
;    :database ig/ref :duct.database/sql
;    :logger ig/ref :duct/logger
;    :strategy :rebase
;   }})

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:duct/daemon
                                         :duct.migrator/ragtime
                                         :duct.scheduler/simple])
        profiles [:duct.profile/prod]
        base-config (duct/read-config (duct/resource "yki/config.edn"))
        external-config (duct/read-config (read-external-config!))]
    (->
     (duct/merge-configs base-config external-config)
     (duct/exec-config profiles keys))))
