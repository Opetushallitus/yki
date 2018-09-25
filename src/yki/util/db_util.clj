(ns yki.util.db-util
  (:require
   [clj-time.local :as l]
   [clj-time.format :as f]
   [clj-time.jdbc]
   [cheshire.core :as json]
   [clojure.java.jdbc :as jdbc]))

(extend-protocol jdbc/IResultSetReadColumn
  java.sql.Date
  (result-set-read-column [d _ _] (l/to-local-date-time d))
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
