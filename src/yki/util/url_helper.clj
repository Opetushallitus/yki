(ns yki.util.url-helper
  (:require [integrant.core :as ig])
  (:import [fi.vm.sade.properties OphProperties]))

(defonce ^fi.vm.sade.properties.OphProperties url-properties (atom nil))

(defmethod ig/init-key :yki.util/url-helper [_ {:keys [virkailija-host tunnistus-host yki-host liiteri-host protocol-base] :or
                                                {virkailija-host "" tunnistus-host ""
                                                 yki-host "" liiteri-host "" protocol-base "https"}}]
  (reset! url-properties
          (doto (OphProperties. (into-array String ["/yki/yki-oph.properties"]))
            (.addDefault "base-protocol" protocol-base)
            (.addDefault "host-virkailija" virkailija-host)
            (.addDefault "host-tunnistus" tunnistus-host)
            (.addDefault "host-liiteri" liiteri-host)
            (.addDefault "host-yki" yki-host)))

  (defn resolve-url
    [key & params]
    (.url @url-properties (name key) (to-array (or params [])))))

