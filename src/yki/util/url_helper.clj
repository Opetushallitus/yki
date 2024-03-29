(ns yki.util.url-helper
  (:require [integrant.core :as ig])
  (:import [fi.vm.sade.properties OphProperties]))

(defonce url-properties (atom nil))

(defn resolve-url
  [key & params]
  (.url ^OphProperties @url-properties (name key) (to-array (or params []))))

(defmethod ig/init-key
  :yki.util/url-helper
  [_ {:keys [virkailija-host oppija-host oppija-sub-domain yki-register-host yki-host-virkailija alb-host scheme cas-service-base cas-oppija-service-base kayttooikeus-service-base koodisto-service-base onr-service-base organisaatio-service-base]
      :or   {virkailija-host "" oppija-host "" oppija-sub-domain "" yki-register-host "" yki-host-virkailija "" alb-host "" scheme "https" cas-service-base "" cas-oppija-service-base "" kayttooikeus-service-base "" koodisto-service-base "" onr-service-base "" organisaatio-service-base ""}}]
  (reset! url-properties
          (doto (OphProperties. (into-array String ["/yki/yki-oph.properties"]))
            (.addDefault "scheme" scheme)
            (.addDefault "host-virkailija" virkailija-host)
            (.addDefault "host-oppija" oppija-host)
            (.addDefault "sub-domain-oppija" oppija-sub-domain)
            (.addDefault "host-alb" alb-host)
            (.addDefault "host-yki-register" yki-register-host)
            (.addDefault "host-yki-virkailija" yki-host-virkailija)
            (.addDefault "cas-service-base" cas-service-base)
            (.addDefault "cas-oppija-service-base" cas-oppija-service-base)
            (.addDefault "kayttooikeus-service-base" kayttooikeus-service-base)
            (.addDefault "koodisto-service-base" koodisto-service-base)
            (.addDefault "onr-service-base" onr-service-base)
            (.addDefault "organisaatio-service-base" organisaatio-service-base)))
  resolve-url)
