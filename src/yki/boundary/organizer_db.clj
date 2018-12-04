(ns yki.boundary.organizer-db
  (:require [jeesql.core :refer [require-sql]]
            [clj-time.coerce :as c]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [yki.boundary.db-extensions]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [duct.database.sql]))

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
  (create-attachment-metadata! [db oid attachment-type external-id]))

(extend-protocol Organizers
  duct.database.sql.Boundary
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
      (try
        (q/delete-organizer-languages! tx {:oid oid})
        (let [deleted (q/delete-organizer! tx {:oid oid})
              oids (map :office_oid (q/select-exam-session-office-oids tx {:oid oid}))]
          (when (= deleted 1)
            (send-to-queue-fn (conj oids oid))
            deleted))
        (catch Exception e
          (.rollback (:connection tx))
          (log/error e "Execution failed. Rolling back transaction.")
          (throw e)))))
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
    (q/select-organizers-by-oids spec {:oids oids})))

