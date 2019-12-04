(ns yki.util.url-helper
  (:require [integrant.core :as ig])
  (:import [fi.vm.sade.properties OphProperties]))

(defonce ^fi.vm.sade.properties.OphProperties url-properties (atom nil))

(defmethod ig/init-key :yki.util/url-helper [_ {:keys [virkailija-host oppija-host yki-register-host tunnistus-host yki-host-virkailija alb-host scheme] 
                                                :or   {virkailija-host "" tunnistus-host "" oppija-host "" yki-register-host "" yki-host-virkailija "" alb-host "" scheme "https"}}]
  (reset! url-properties
          (doto (OphProperties. (into-array String ["/yki/yki-oph.properties"]))
            (.addDefault "scheme" scheme)
            (.addDefault "host-virkailija" virkailija-host)
            (.addDefault "host-oppija" oppija-host)
            (.addDefault "host-tunnistus" tunnistus-host)
            (.addDefault "host-alb" alb-host)
            (.addDefault "host-yki-register" yki-register-host)
            (.addDefault "host-yki-virkailija" yki-host-virkailija)))

  (defn resolve-url
    [key & params]
    (.url @url-properties (name key) (to-array (or params [])))))

