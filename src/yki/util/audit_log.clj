(ns yki.util.audit-log
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [yki.util.log-util :as log-util])
  (:import [java.net InetAddress]
           [org.ietf.jgss Oid]
           [com.google.gson JsonParser]
           [com.fasterxml.jackson.datatype.joda JodaModule]
           [fi.vm.sade.auditlog Audit ApplicationType User Operation Target$Builder Changes Changes$Builder]))

(def mapper
  (json/object-mapper
   {:modules [(JodaModule.)]}))

(def update-op "update")
(def create-op "create")
(def delete-op "delete")
(def cancel-op "cancel")
(def organizer "organizer")
(def payment "payment")
(def registration "registration")
(def registration-init "registration-init")
(def exam-session "exam-session")
(def exam-date "exam-date")

(defonce jsonParser (JsonParser.))

(defonce ^fi.vm.sade.auditlog.Logger logger-proxy
  (reify fi.vm.sade.auditlog.Logger
    (log [this msg]
      (log/info msg))))

(defn ^fi.vm.sade.auditlog.Operation op [operation]
  (reify fi.vm.sade.auditlog.Operation
    (name [this]
      operation)))

(defn- ->json-string [value]
  (json/write-value-as-string value mapper))

(defonce ^:private virkailija-logger (Audit. logger-proxy "yki" ApplicationType/VIRKAILIJA))

(defonce ^:private oppija-logger (Audit. logger-proxy "yki" ApplicationType/OPPIJA))

(defn- create-changes [change]
  (case (:type change)
    "update" (let [old  (.getAsJsonObject (.parse jsonParser (->json-string (:old change))))
                   new  (.getAsJsonObject (.parse jsonParser (->json-string (:new change))))]
               (Changes/updatedDto new old))
    "delete" (.build (Changes$Builder.))
    "cancel" (.build (Changes$Builder.))
    "create" (let [new  (.getAsJsonObject (.parse jsonParser (->json-string (:new change))))]
               (Changes/addedDto new))))

(defn- oid-or-nil [oid]
  (if oid
    (Oid. oid)))

(defn log
  [{:keys [request target-kv change]}]
  (try
    (let [inet-address    (InetAddress/getLocalHost)
          session         (:session request)
          oid             (get-in session [:identity :oid])
          user-agent      ((:headers request) "user-agent")
          user            (User. (oid-or-nil oid) inet-address (:yki-session-id session) user-agent)
          op              (op (:type change))
          target          (-> (Target$Builder.)
                              (.setField (:k target-kv) (str (:v target-kv)))
                              (.build))
          changes         (create-changes change)]
      (.log virkailija-logger user op target changes))
    (catch Exception e
      (log-util/error e "Virkailija audit logging failed for data:" change))))

(defn log-participant
  [{:keys [request target-kv change oid]}]
  (try
    (let [inet-address    (InetAddress/getLocalHost)
          session         (:session request)
          oid             (or oid (get-in session [:identity :oid]))
          user-agent      ((:headers request) "user-agent")
          user            (User. (oid-or-nil oid) inet-address (:yki-session-id session) user-agent)
          op              (op (:type change))
          target          (-> (Target$Builder.)
                              (.setField (:k target-kv) (str (:v target-kv)))
                              (.build))
          changes         (create-changes change)]
      (.log oppija-logger user op target changes))
    (catch Exception e
      (log-util/error e "Participant audit logging failed for data:" change))))
