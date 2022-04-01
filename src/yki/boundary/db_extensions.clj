(ns yki.boundary.db-extensions
  (:require
    [clj-time.jdbc]
    [clj-time.local :as l]
    [clojure.data.json :as json]
    [clojure.java.jdbc :as jdbc]
    [yki.util.common :as c])
  (:import
    (clojure.lang IPersistentCollection)
    (java.sql Date Time)
    (org.joda.time DateTime)
    (org.postgresql.jdbc PgArray)
    (org.postgresql.util PGobject)))

(extend-protocol jdbc/ISQLValue
  IPersistentCollection
  (sql-value [value]
    (doto (PGobject.)
      (.setType "jsonb")
      (.setValue (json/write-str value)))))

(defn- date-time->str [^DateTime date-time]
  (.toString date-time ^String c/date-format))

(extend-protocol jdbc/IResultSetReadColumn
  Date
  (result-set-read-column [^Date val _ _]
    (-> val
        (l/to-local-date-time)
        (date-time->str)))
  Time
  (result-set-read-column [^Time val _ _]
    (-> val
        (l/to-local-date-time)
        (date-time->str)))
  PgArray
  (result-set-read-column [pgobj _ _]
    (remove nil? (vec (.getArray pgobj))))
  PGobject
  (result-set-read-column [pgobj _ _]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        ("json" "jsonb") (json/read-str value {:key-fn keyword})
        :else value))))
