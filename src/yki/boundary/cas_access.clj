(ns yki.boundary.cas-access
  (:require
   [integrant.core :as ig]
   [clj-util.cas :as cas]))

(defprotocol CasAccess
  (validate-ticket [this ticket]))

(defrecord CasClient [url-helper]
  CasAccess
  (validate-ticket [this ticket]
    (let [cas-client (cas/cas-client (url-helper :cas-client))
          username   (.run (.validateServiceTicket cas-client (url-helper :yki.cas.login-success) ticket))]
      username)))

(defmethod ig/init-key :yki.boundary.cas-access/cas-access [_ {:keys [url-helper]}]
  (defn cas [url-helper]
    (->CasClient url-helper)))
