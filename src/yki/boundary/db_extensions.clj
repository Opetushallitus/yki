(ns yki.boundary.db-extensions
  (:require
   [yki.util.common :as c]
   [clj-time.local :as l]
   [clj-time.format :as f]
   [clj-time.jdbc]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clojure.java.jdbc :as jdbc])
  (:import
   (org.postgresql.util PGobject)))

(extend-protocol jdbc/ISQLValue
  clojure.lang.IPersistentCollection
  (sql-value [value]
    (doto (PGobject.)
      (.setType "jsonb")
      (.setValue (json/generate-string value)))))

(extend-protocol jdbc/IResultSetReadColumn
  java.sql.Date
  (result-set-read-column [d _ _] (.toString (l/to-local-date-time d) c/date-format))
  java.sql.Time
  (result-set-read-column [d _ _] (.toString (l/to-local-date-time d) c/time-format))
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj _ _]
    (remove nil? (vec (.getArray pgobj))))
  org.postgresql.util.PGobject
  (result-set-read-column [pgobj _ _]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/parse-string value true)
        "jsonb" (json/parse-string value true)
        :else value))))
