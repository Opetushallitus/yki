(ns yki.embedded-db
  (:require [clojure.java.jdbc :as jdbc]
            [ragtime.jdbc]
            [ragtime.core :as core])
  (:import (com.opentable.db.postgres.embedded EmbeddedPostgres)))

(defonce conn (atom nil))

(defonce port (atom nil))

(defn db-spec []
  {:classname   "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname     (str
                  "//localhost:"
                  @port
                  "/postgres")
   :user        "postgres"})

(defn with-postgres [f]
  (let [db (-> (EmbeddedPostgres/builder)
               (.start))]
    (try
      (reset! port (.getPort db))
      (f)
      (finally
        (reset! port nil)
        (.close db)))))

(defn with-migration [f]
  (let [db (ragtime.jdbc/sql-database (db-spec))
        ms (ragtime.jdbc/load-directory "resources/yki/migrations")
        idx (core/into-index ms)]
    (core/migrate-all db idx ms))
  (f))

(defn with-transaction [f]
  (jdbc/with-db-transaction [tx (db-spec)]
    (jdbc/db-set-rollback-only! tx)
    (reset! conn tx)
    (f)))
