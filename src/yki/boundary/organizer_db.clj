(ns yki.boundary.organizer-db
  (:require [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [organizer]
  (reduce #(update-in %1 [%2] f/parse) organizer [:agreement_start_date
                                                  :agreement_end_date]))

(defprotocol Organizers
  (create-organizer! [db organizer])
  (delete-organizer! [db oid send-to-queue-fn])
  (update-organizer! [db oid organizer])
  (get-organizers [db])
  (get-organizers-by-oids [db oids])
  (get-attachment-metadata [db external-id oid])
  (create-attachment-metadata! [db oid attachment-type external-id]))

(extend-protocol Organizers
  Boundary
  (create-organizer!
    [{:keys [spec]} organizer]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-organizer! tx (convert-dates organizer))
      (doseq [lang (:languages organizer)]
        (q/insert-organizer-language! tx (merge lang {:oid (:oid organizer)})))
      true))
  (create-attachment-metadata!
    [{:keys [spec]} oid attachment-type external-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-attachment-metadata! tx {:oid oid :external_id external-id :type attachment-type})))
  (delete-organizer!
    [{:keys [spec]} oid send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [deleted (q/delete-organizer! tx {:oid oid})
               oids    (map :office_oid (q/select-exam-session-office-oids tx {:oid oid}))]
           (when (= deleted 1)
             (send-to-queue-fn (conj oids oid))
             deleted)))))
  (update-organizer!
    [{:keys [spec]} oid organizer]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-organizer-languages! tx {:oid oid})
      (doseq [lang (:languages organizer)]
        (q/insert-organizer-language! tx (merge lang {:oid oid})))
      (q/update-organizer! tx (assoc (convert-dates organizer) :oid oid))))
  (get-organizers [{:keys [spec]}]
    (q/select-organizers spec))
  (get-organizers-by-oids [{:keys [spec]} oids]
    (q/select-organizers-by-oids spec {:oids oids}))
  (get-attachment-metadata [{:keys [spec]} external-id oid]
    (q/select-attachment-metadata spec {:external_id external-id :oid oid})))

