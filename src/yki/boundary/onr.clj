(ns yki.boundary.onr
  (:require
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [yki.boundary.cas :as cas]))

(defprotocol Onr
  (get-or-create-person [this person])
  (get-person-by-ssn [this ssn])
  (get-person-by-oid [this oid]))

(defrecord OnrClient [url-helper cas-client]
  Onr
  (get-or-create-person [_ person]
    (let [url (url-helper :onr-service.get-or-create-person)
          {:keys [status body]} (cas/cas-authenticated-post cas-client url person)]
      (if (or (= 200 status) (= 201 status))
        ((json/read-value body) "oidHenkilo")
        (log/error "ONR get-or-create-person request:" (str person " status: " status " : " body)))))
  (get-person-by-ssn [_ ssn]
    (let [url (url-helper :onr-service.person-by-ssn ssn)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (log/info "ONR get-person-by-ssn error:" status))))
  (get-person-by-oid [_ oid]
    (let [url (url-helper :onr-service.person-by-oid oid)
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (json/read-value body)
        (log/error "ONR get-person-by-oid error:" status)))))

(defmethod ig/init-key :yki.boundary.onr/onr-client [_ {:keys [url-helper cas-client]}]
  (->OnrClient url-helper (cas-client "/oppijanumerorekisteri-service")))
