(ns yki.boundary.db-extensions
  (:require
   [clj-time.local :as l]
   [clj-time.format :as f]
   [clj-time.jdbc]
   [cheshire.core :as json]
   [clojure.java.jdbc :as jdbc]))

(extend-protocol jdbc/IResultSetReadColumn
  java.sql.Date
  (result-set-read-column [d _ _] (l/to-local-date-time d))
  java.sql.Time
  (result-set-read-column [d _ _] (.toString (l/to-local-date-time d) "HH:mm"))
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj _ _]
    (remove nil? (vec (.getArray pgobj))))
  org.postgresql.util.PGobject
  (result-set-read-column [pgobj _ _]
    (let [type (.getType pgobj)
          value (.getValue pgobj)]
      (if (= "json" type)
        (json/parse-string value true)
        value))))
