(ns yki.boundary.onr
  (:require
   [integrant.core :as ig]
   [clojure.tools.logging :refer [error info]]
   [yki.boundary.cas :as cas]
   [jsonista.core :as json]))

(defprotocol Onr
  (get-person-by-ssn [this ssn]))

(defrecord OnrClient [url-helper cas-client]
  Onr
  (get-person-by-ssn [_ ssn]
    (let [url (url-helper :onr-service.henkilo-by-hetu ssn)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (error "ONR request error:" (str status " : " body))))))

(defmethod ig/init-key :yki.boundary.onr/onr-client [_ {:keys [url-helper cas-client]}]
  (->OnrClient url-helper (cas-client "/oppijanumerorekisteri-service")))
