(ns yki.embedded-db
  (:require [clojure.test :as t]
            [clojure.java.jdbc :as jdbc]
            [ragtime.jdbc]
            [ragtime.core :as core]
            [ragtime.jdbc.migrations :refer :all]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket]
           [com.opentable.db.postgres.embedded EmbeddedPostgres]))

(defn- get-free-port! []
  (let [socket (ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defonce conn (atom nil))

(defonce port (get-free-port!))

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
    (core/migrate-all db idx ms))
  (f))

(defn with-transaction [f]
  (jdbc/with-db-transaction [tx db-spec]
    (jdbc/db-set-rollback-only! tx)
    (reset! conn tx)
    (f)))
