(ns yki.util.audit-log
  (:require
    [clj-json-patch.core :as patch]
    [clj-json-patch.util :refer [apply-patch get-patch-value]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk :refer [stringify-keys]]
    [jsonista.core :as json]
    [yki.util.log-util :as log-util])
  (:import [java.net InetAddress]
           [org.ietf.jgss Oid]
           [com.google.gson JsonArray JsonElement JsonParser]
           [com.fasterxml.jackson.datatype.joda JodaModule]
           [fi.vm.sade.auditlog Audit ApplicationType Logger Operation User Target$Builder Changes Changes$Builder]))

(def mapper
  (json/object-mapper
    {:modules [(JodaModule.)]}))

(def update-op "update")
(def create-op "create")
(def delete-op "delete")
(def cancel-op "cancel")
(def organizer "organizer")
(def payment "payment")
(def evaluation-payment "evaluation-payment")
(def registration "registration")
(def registration-init "registration-init")
(def exam-session "exam-session")
(def exam-date "exam-date")
(def quarantine "quarantine")
(def quarantine-review "quarantine-review")

(defonce ^JsonParser jsonParser (JsonParser.))

(defonce ^Logger logger-proxy
         (reify Logger
           (log [_ msg]
             (log/info msg))))

(defn op ^Operation [operation]
  (reify Operation
    (name [_]
      operation)))

(defn- ->json-string ^String [value]
  (json/write-value-as-string value mapper))

(defonce ^:private ^Audit virkailija-logger (Audit. logger-proxy "yki" ApplicationType/VIRKAILIJA))

(defonce ^:private ^Audit oppija-logger (Audit. logger-proxy "yki" ApplicationType/OPPIJA))

(defn- format-json-path [^String path]
  (-> (subs path 1)
      (str/replace #"/" ".")))

(defn- process-patch-array [value patch-array]
  (try
    (loop [current-value     value
           patches           patch-array
           processed-patches []
           iteration         0]
      (if (seq patches)
        (if (< 100 iteration)
          (throw (ex-info "Iteration limit reached!" {}))
          (let [current-patch  (first patches)
                {op             "op"
                 path           "path"
                 new-path-value "value"} current-patch
                old-path-value (and
                                 (#{"replace" "remove"} op)
                                 (get-patch-value current-value (get current-patch "path")))]
            (log-util/info (assoc current-patch "iteration" iteration))
            (recur
              (apply-patch current-value current-patch)
              (rest patches)
              (conj processed-patches {"newValue" new-path-value
                                       "oldValue" old-path-value
                                       "op"       op
                                       "path"     path})
              (inc iteration))))
        processed-patches))
    (catch Exception e
      (log-util/error e "Post-processing patch array failed. Returning instead original patch array for audit logging: " patch-array)
      patch-array)))

(defn- log-and-return [stage value]
  (log-util/info {:stage stage
                  :value value})
  value)

(defn- update->json-array [old new]
  (let [old         (stringify-keys old)
        new         (stringify-keys new)
        _           (log-util/info {:old old, :new new})
        patch-array (patch/diff old new)
        _           (log-util/info {:diff patch-array})]
    (->> patch-array
         (log-and-return "after-patch-array")
         (process-patch-array old)
         (log-and-return "after-processed")
         (mapv #(update % "path" format-json-path))
         (log-and-return "after-mapv")
         (->json-string)
         ^String (log-and-return "after-json-string")
         (.parse jsonParser)
         ^JsonElement (log-and-return "after-parse")
         (.getAsJsonArray))))

(defn- create-changes ^JsonArray [change]
  (case (:type change)
    "update" (let [old (:old change)
                   new (:new change)]
               (update->json-array old new))
    "delete" (.asJsonArray (.build (Changes$Builder.)))
    "cancel" (.asJsonArray (.build (Changes$Builder.)))
    "create" (let [new (.getAsJsonObject (.parse jsonParser (->json-string (:new change))))]
               (.asJsonArray (Changes/addedDto new)))))

(defn- oid-or-nil [^String oid]
  (when oid
    (Oid. oid)))

(defn log
  [{:keys [request target-kv change]}]
  (try
    (let [inet-address (InetAddress/getLocalHost)
          session      (:session request)
          oid          (get-in session [:identity :oid])
          user-agent   ((:headers request) "user-agent")
          user         (User. (oid-or-nil oid) inet-address (:yki-session-id session) user-agent)
          op           (op (:type change))
          target       (-> (Target$Builder.)
                           (.setField (:k target-kv) (str (:v target-kv)))
                           (.build))
          changes      (create-changes change)]
      (.log virkailija-logger user op target changes))
    (catch Exception e
      (log-util/error e "Virkailija audit logging failed for data:" change))))

(defn log-participant
  [{:keys [request target-kv change oid]}]
  (try
    (let [inet-address (InetAddress/getLocalHost)
          session      (:session request)
          oid          (or oid (get-in session [:identity :oid]))
          user-agent   ((:headers request) "user-agent")
          user         (User. (oid-or-nil oid) inet-address (:yki-session-id session) user-agent)
          op           (op (:type change))
          target       (-> (Target$Builder.)
                           (.setField (:k target-kv) (str (:v target-kv)))
                           (.build))
          changes      (create-changes change)]
      (.log oppija-logger user op target changes))
    (catch Exception e
      (log-util/error e "Participant audit logging failed for data:" change))))
