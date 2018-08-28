(ns yki.boundary.organizer_db
    (:require [jeesql.core :refer [require-sql]]
              [clj-time.coerce :as c]
              [clj-time.format :as f]
              [clj-time.jdbc]
              [cheshire.core :as json]
              [clojure.java.jdbc :as jdbc]
              [duct.database.sql]))

  (require-sql ["yki/queries.sql" :as q])

  (extend-protocol jdbc/IResultSetReadColumn
    org.postgresql.jdbc.PgArray
    (result-set-read-column [pgobj metadata i]
      (remove nil? (vec (.getArray pgobj))))
    org.postgresql.util.PGobject
    (result-set-read-column [pgobj metadata i]
      (let [type (.getType pgobj)
            value (.getValue pgobj)]
        (if (= "json" type)
          (json/parse-string value true)
          value))))

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
      (jdbc/with-db-transaction [tx spec]
        (-> (q/insert-organizer! tx (convert-dates organizer)))
        (doseq [lang (:languages organizer)]
          (-> (q/insert-organizer-language! tx {:language_code lang :level_code "PERUS" :oid (:oid organizer)})))
          true))
    (delete-organizer!
      [{:keys [spec]} oid]
      (jdbc/with-db-transaction [tx spec]
        (-> (q/delete-organizer-languages! tx {:oid oid}))
        (-> (q/delete-organizer! tx {:oid oid}))))
    (update-organizer!
      [{:keys [spec]} oid organizer]
      "update organizer using oid from arguments"
      (jdbc/with-db-transaction [tx spec]
        (-> (q/delete-organizer-languages! tx {:oid oid}))
        (doseq [lang (:languages organizer)]
          (-> (q/insert-organizer-language! tx {:language_code lang :level_code "PERUS" :oid oid})))
        (-> (q/update-organizer! tx (assoc-in (convert-dates organizer) [:oid] oid)))))
    (get-organizers [{:keys [spec]}]
      (-> (q/select-organizers spec))))
