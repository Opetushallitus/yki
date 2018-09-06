(ns yki.util.url-helper
  (:require [integrant.core :as ig])
  (:import [fi.vm.sade.properties OphProperties]))

(def ^fi.vm.sade.properties.OphProperties url-properties (atom nil))

; (defn resolve-url
;   [key & params]
;   (.url @url-properties (name key) (to-array (or params []))))

(defmethod ig/init-key :yki.util/url-helper [_ {:keys [virkailija-host yki-host] :or
                                                {virkailija-host "" yki-host ""}}]
  (reset! url-properties
          (doto (OphProperties. (into-array String ["/yki/yki-oph.properties"]))
            (.addDefault "host-virkailija" virkailija-host)
            (.addDefault "host-yki" yki-host)))
  (println "resetting2!")
  (defn resolve-url
    [key & params]
    (.url @url-properties (name key) (to-array (or params [])))))

