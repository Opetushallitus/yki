(ns yki.util.audit-log
  (:require [clojure.tools.logging :refer [info]]
            [jsonista.core :as json])
  (:import [java.net InetAddress]
           [org.ietf.jgss Oid]
           [com.google.gson JsonParser]
           [com.fasterxml.jackson.datatype.joda JodaModule]
           [fi.vm.sade.auditlog Audit ApplicationType User Operation Target$Builder Changes$Builder]))

(def mapper
  (json/object-mapper
   {:modules [(JodaModule.)]}))

(def update-op "update")
(def create-op "create")
(def delete-op "delete")
(def organizer "organizer")
(def exam-session "exam-session")

(defonce jsonParser (JsonParser.))

(defonce ^fi.vm.sade.auditlog.Logger logger-proxy
  (reify fi.vm.sade.auditlog.Logger
    (log [this msg]
      (info msg))))

(defn ^fi.vm.sade.auditlog.Operation op [operation]
  (reify fi.vm.sade.auditlog.Operation
    (name [this]
      operation)))

(defn- ->json-string [value]
  (json/write-value-as-string value mapper))

(defonce ^:private virkailija-logger (Audit. logger-proxy "yki" ApplicationType/VIRKAILIJA))

(defonce ^:private oppija-logger (Audit. logger-proxy "yki" ApplicationType/OPPIJA))

(defn- add-changes [builder change]
  (case (:type change)
    "update" (let [old  (-> (.parse jsonParser (->json-string (:old change)))
                            (.getAsJsonObject))
                   new  (-> (.parse jsonParser (->json-string (:new change)))
                            (.getAsJsonObject))]
               (.updated builder "object" old new))
    "delete" builder
    "create" (let [new  (-> (.parse jsonParser (->json-string (:new change)))
                            (.getAsJsonObject))]
               (.added builder "object" new))))

(defn- oid-or-nil [oid]
  (if oid
    (Oid. oid)))

(defn log
  [{:keys [request target-kv change]}]
  (let [inet-address    (InetAddress/getLocalHost)
        session         (:session request)
        oid             (get-in session [:identity :oid])
        user-agent      ((:headers request) "user-agent")
        user            (User. (oid-or-nil oid) inet-address (:yki-session-id session) user-agent)
        op              (op (:type change))
        target          (-> (Target$Builder.)
                            (.setField (:k target-kv) (:v target-kv))
                            (.build))
        changes         (-> (Changes$Builder.)
                            (add-changes change)
                            (.build))]

    (.log virkailija-logger user op target changes)))
