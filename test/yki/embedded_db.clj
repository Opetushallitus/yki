(ns yki.embedded-db
  (:require [clojure.test :as t]
            [clojure.java.jdbc :as jdbc]
            [ragtime.jdbc]
            [ragtime.core :as core]
            [ragtime.jdbc.migrations :refer :all]
            [clojure.java.io :as io])
  (:import [com.opentable.db.postgres.embedded EmbeddedPostgres]))

  ; TODO find free port
(def port 59432)

(def db-spec {:classname "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname (str
                        "//localhost:"
                        port
                        "/postgres")
              :user "postgres"})

(defn with-postgres [f]
  (let [db (-> (EmbeddedPostgres/builder)
                      (.setPort port)
                      (.start))]
    (try
      (f)
      (finally
        (.close db)))))

(defn with-migration [f]
  (let [db  (ragtime.jdbc/sql-database db-spec)
        ms  (ragtime.jdbc/load-directory "resources/yki/migrations")
        idx (core/into-index ms)]
    (println "execute migration")
    (core/migrate-all db idx ms))
  (f))

