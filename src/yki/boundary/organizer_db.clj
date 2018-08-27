(ns yki.boundary.organizer_db
    (:require [jeesql.core :refer [require-sql]]
              [clj-time.coerce :as c]
              [clj-time.format :as f]
              [clj-time.jdbc]
              [clojure.java.jdbc :as jdbc]
              [duct.database.sql]))

  (require-sql ["yki/queries.sql" :as q])

  (extend-protocol jdbc/IResultSetReadColumn
    org.postgresql.jdbc.PgArray
    (result-set-read-column [pgobj metadata i]
      (remove nil? (vec (.getArray pgobj)))))

  (defn- convert-dates [{:keys [oid agreement_start_date agreement_end_date contact_name contact_email contact_phone_number]}]
    {:oid oid
      :contact_name contact_name
      :contact_email contact_email
      :contact_phone_number contact_phone_number
      :agreement_start_date (f/parse agreement_start_date)
      :agreement_end_date (f/parse agreement_end_date)})

  (defprotocol Organizers
    (create-organizer! [db organizer])
    (delete-organizer! [db oid])
    (update-organizer! [db oid organizer])
    (get-organizers [db]))

  (extend-protocol Organizers
    duct.database.sql.Boundary
    (create-organizer!
      [{:keys [spec]} organizer]
      (-> (q/insert-organizer! spec (convert-dates organizer))))
    (delete-organizer!
      [{:keys [spec]} oid]
      (-> (q/delete-organizer! spec {:oid oid})))
    (update-organizer!
      [{:keys [spec]} oid organizer]
      "update organizer using oid from arguments"
      (-> (q/update-organizer! spec (assoc-in (convert-dates organizer) [:oid] oid))))
    (get-organizers [{:keys [spec]}]
      (-> (q/select-organizers spec))))
