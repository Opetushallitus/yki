(ns yki.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [duct.core :as duct]))

(defn- read-external-config []
  (let [config-for-docker (io/file "./oph-configuration/config.edn")]
    (when (.exists config-for-docker)
      config-for-docker)))

(defn setup-logback-configuration! []
  (let [dockerized-logback-configuration "./oph-configuration/logback.xml"]
    (when (.exists (io/file dockerized-logback-configuration))
      (System/setProperty "logback.configurationFile" dockerized-logback-configuration))))

; (defn migrator-config [migrations]
;   {:duct.migrator/ragtime {:migrations (vec migrations)
;    :database ig/ref :duct.database/sql
;    :logger ig/ref :duct/logger
;    :strategy :rebase
;   }})

(defn -main [& args]
  ; Setup system property for logback configuration file as the first operation,
  ; as it won't have any effect if it is set after the logging mechanism is instantiated.
  (setup-logback-configuration!)
  ; Manually require scheduled-tasks to ensure that the tasks get scheduled
  ; when the application is started. Not requiring as part of the ns macro to ensure
  ; this doesn't interfere with the logging setup.
  (require '(yki.job.scheduled-tasks))
  (duct/load-hierarchy)
  (let [keys            (or (duct/parse-keys args) [:duct/daemon
                                                    :duct.migrator/ragtime
                                                    :duct.scheduler/simple])
        profiles        [:duct.profile/prod]
        base-config     (duct/read-config (duct/resource "yki/config.edn"))
        external-config (duct/read-config (read-external-config))]
    (->
      (duct/merge-configs base-config external-config)
      (duct/exec-config profiles keys))))
