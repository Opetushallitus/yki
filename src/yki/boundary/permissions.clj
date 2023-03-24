(ns yki.boundary.permissions
  (:require
   [integrant.core :as ig]
   [yki.boundary.cas :as cas]
   [jsonista.core :as json]))

(defprotocol Permissions
  (virkailija-by-username [this username]))

(defrecord PermissionsClient [url-helper cas-client]
  Permissions
  (virkailija-by-username [_ username]
    (let [url (url-helper :kayttooikeus-service.kayttooikeus.kayttaja
                          {"username" username})
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (if-let [virkailija (first (json/read-value body (json/object-mapper {:decode-key-fn true})))]
          virkailija
          (throw (new RuntimeException
                      (str "No virkailija found by username " username))))
        (throw (new RuntimeException
                    (str "Could not get virkailija by username " username
                         ", status: " status
                         ", body: " body)))))))

(defmethod ig/init-key :yki.boundary.permissions/permissions-client [_ {:keys [url-helper cas-client]}]
  (let [permissions-cas-client (cas-client (url-helper :kayttooikeus-service))]
    (->PermissionsClient url-helper permissions-cas-client)))
