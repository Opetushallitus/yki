(ns yki.boundary.onr
  (:require
   [integrant.core :as ig]
   [clojure.tools.logging :refer [error info]]
   [yki.boundary.cas :as cas]
   [jsonista.core :as json]))

(defprotocol Onr
  (get-or-create-person [this person])
  (get-person-by-ssn [this ssn]))

(defrecord OnrClient [url-helper cas-client]
  Onr
  (get-or-create-person [_ person]
    (let [url (url-helper :onr-service.get-or-create-person)
          {:keys [status body]} (cas/cas-authenticated-post cas-client url person)]
      (if (or (= 200 status) (= 201 status))
        (let [json-body (json/read-value body)]
          (info "onr response:" json-body)
          (json-body "henkiloOid"))
        (error "ONR get-or-create-person request:" (str person " status: " status " : " body)))))
  (get-person-by-ssn [_ ssn]
    (let [url (url-helper :onr-service.henkilo-by-hetu ssn)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (error "ONR get-person-by-ssn error:" (str status " : " body))))))

(defmethod ig/init-key :yki.boundary.onr/onr-client [_ {:keys [url-helper cas-client]}]
  (->OnrClient url-helper (cas-client "/oppijanumerorekisteri-service")))
