(ns yki.boundary.organizer_db
    (:require [jeesql.core :refer [require-sql]]
              [clj-time.coerce :as c]
              [duct.database.sql]))

  (require-sql ["yki/queries.sql" :as q])

  (defn- convert-dates [{:keys [oid agreement_start_date agreement_end_date contact_name contact_email contact_phone_number]}]
    {:oid oid
      :contact_name contact_name
      :contact_email contact_email
      :contact_phone_number contact_phone_number
      :agreement_start_date (c/to-sql-date agreement_start_date)
      :agreement_end_date (c/to-sql-date agreement_end_date)})
  
  (defprotocol Organizers
    (create-organizer! [db organizer])
    (delete-organizer! [db oid])
    (get-organizers [db]))

  (extend-protocol Organizers
    duct.database.sql.Boundary
    (create-organizer! [{:keys [spec]} organizer]
      (-> (q/insert-organizer! spec (convert-dates organizer))))
    (delete-organizer! [{:keys [spec]} oid]
      (-> (q/delete-organizer! spec {:oid oid})))
    (get-organizers [{:keys [spec]}]
      (-> (q/select-organizers spec))))