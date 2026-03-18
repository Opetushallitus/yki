(ns yki.migrations
  (:require [integrant.core :as ig]
            [ragtime.jdbc :as ragtime-jdbc]))

(defmethod ig/init-key :yki/migrations [_ _]
  (vec (ragtime-jdbc/load-resources "yki/migrations")))
