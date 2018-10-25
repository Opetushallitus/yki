(ns yki.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [duct.core :as duct]))

(duct/load-hierarchy)

(defn- read-external-config []
  (if (.exists (io/file "./oph-configuration/config.edn"))
    (io/file "./oph-configuration/config.edn")))

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:duct/daemon :duct.migrator/ragtime])]
    (-> (duct/read-config (read-external-config))
        (duct/prep keys)
        (duct/exec keys))))
