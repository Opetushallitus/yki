(ns yki.boundary.exam-session-db
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
            [yki.registration.change-event :refer [registration->change-event]]
            [yki.util.db :refer [rollback-on-exception]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/queries.sql" :as q])

(defn- convert-dates [exam-session]
  (reduce #(update-in %1 [%2] f/parse) exam-session [:session_date
                                                     :published_at]))

(defn- int->boolean [value]
  (= value 1))

(defn add-and-link-contact
  "Takes the first contact on the list and adds a new contact to org if it does not exist yet.
   Links said contact to the exam session. Data and data model support multiple contacts but
   for now only one is handled.."
  [tx spec oid exam-session-id contact-list]
  (log/info "Add and link contact" contact-list "from org" oid "to exam session" exam-session-id)
  (if contact-list
    (let [contact-meta   (first contact-list)
          does-not-exist (->> (assoc contact-meta :exam_session_id exam-session-id)
                              (q/select-existing-session-contact tx)
                              (first)
                              (:id)
                              (nil?))]
      (when (and contact-meta does-not-exist)
        (let [contact         (assoc contact-meta :oid oid)
              session-id      {:exam_session_id exam-session-id}
              get-link-params (fn [contact-id] (assoc session-id :contact_id contact-id))]
          ; For now exam session is allowed to have only one contact so deleting old contact link
          (q/delete-exam-session-contact-by-session-id! tx session-id)
          (if-let [contact-id (->> contact
                                   (q/select-contact-id-with-details spec)
                                   (first)
                                   (:id))]
            ; Contact exists, creating a link
            (when-not (:id (q/select-exam-session-contact-id tx (get-link-params contact-id)))
              (q/insert-exam-session-contact<! tx (get-link-params contact-id)))

            ; Creating a contact and a link
            (let [new-contact-id (:id (q/insert-contact<! tx contact))]
              (q/insert-exam-session-contact<! tx (get-link-params new-contact-id)))))))

    ; Delete link if contact fields are null
    (q/delete-exam-session-contact-by-session-id! tx {:exam_session_id exam-session-id})))

(defn get-transfer-targets-for-exam-session
  "Valid transfer targets are either within a year of the original date, or if no such exam sessions exist, the first available exam session"
  [tx original-exam-date exam-session-id]
  (let [candidates   (q/select-transfer-targets-by-exam-session-id tx {:exam_session_id exam-session-id})
        within-year? #(let [exam-date  (f/parse (:exam_date %1))
                            limit-date (t/plus (f/parse original-exam-date) (t/years 1))]
                        (not (t/after? exam-date limit-date)))
        within-year  (filter within-year? candidates)]
    (cond
      (empty? candidates) []
      (seq within-year) (map :id within-year)
      :else (->> candidates (sort-by :exam_date) first :id vector))))

(defprotocol ExamSessions
  (create-exam-session! [db oid exam-session send-to-queue-fn])
  (update-exam-session! [db oid id exam-session])
  (delete-exam-session! [db id oid send-to-queue-fn])
  (init-participants-sync-status! [db exam-session-id])
  (init-relocated-participants-sync-status! [db exam-session-id])
  (set-participants-sync-to-success! [db exam-session-id])
  (set-participants-sync-to-failed! [db exam-session-id retry-duration])
  (cancel-registration! [db session registration-id])
  (update-registration-exam-session! [db session to-exam-session-id registration-id oid])
  (get-exam-session-by-id [db id])
  (get-exam-session-registration-by-registration-id [db registration-id])
  (get-exam-session-with-location [db id lang])
  (get-exam-session-participants [db id oid])
  (get-completed-exam-session-participants [db id])
  (get-exam-sessions-to-be-synced [db retry-duration])
  (get-exam-sessions [db from]
    "Get exam sessions with exam date at least 'from'")
  (get-exam-sessions-for-oid [db oid from]
    "Get exam sessions by oid and with (optional) exam date at least 'from'")
  (remove-old-entries-from-exam-session-queue! [db])
  (get-contact-info-by-exam-session-id [db id])
  (get-exam-session-location-extra-information [db id lang])
  (get-exam-sessions-for-statistics-sync [db])
  (get-exam-session-statistics [{:keys [spec]} statistics-id])
  (get-initial-statistics-for-exam-session [db exam-session-id])
  (get-unprocessed-events-for-exam-session [{:keys [spec]} exam-session-id last-processed-event-id])
  (update-exam-session-statistics! [db statistics]))

(extend-protocol ExamSessions
  Boundary
  (create-exam-session!
    [{:keys [spec]} oid exam-session send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(let [converted       (merge {:office_oid nil} (assoc (convert-dates exam-session) :oid oid))
               result          (q/insert-exam-session<! tx converted)
               exam-session-id (:id result)]
           (doseq [loc (:location exam-session)]
             (q/insert-exam-session-location! tx (assoc loc :exam_session_id exam-session-id)))

           (add-and-link-contact tx spec oid exam-session-id (:contact exam-session))
           (send-to-queue-fn)
           exam-session-id))))
  (init-participants-sync-status!
    [{:keys [spec]} exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/insert-participants-sync-status! tx {:exam_session_id exam-session-id})))
  (init-relocated-participants-sync-status!
    [{:keys [spec]} exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (let [exam-session (first (q/select-relocated-session-for-sync spec {:exam_session_id exam-session-id}))]
        (when exam-session
          (q/insert-relocated-participants-sync-status! tx {:exam_session_id exam-session-id})))))
  (set-participants-sync-to-success!
    [{:keys [spec]} exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-sync-to-success! tx {:exam_session_id exam-session-id})))
  (set-participants-sync-to-failed!
    [{:keys [spec]} exam-session-id interval]
    (jdbc/with-db-transaction [tx spec]
      (q/update-participant-sync-to-failed! tx {:exam_session_id exam-session-id :interval interval})))
  (update-registration-exam-session!
    [{:keys [spec]} session to-exam-session-id registration-id oid]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        (fn do-relocate! []
          (let [{exam-session-id :id exam-date :exam_date} (q/select-registration-details-for-transfer tx {:id registration-id})
                valid-transfer-targets (get-transfer-targets-for-exam-session
                                         tx
                                         exam-date
                                         exam-session-id)]
            (if (some #{to-exam-session-id} valid-transfer-targets)
              (when-let [updated (q/update-registration-exam-session<!
                                   tx
                                   {:exam_session_id to-exam-session-id
                                    :registration_id registration-id
                                    :oid             oid})]
                (q/insert-registration-change-event!
                  tx
                  (merge (registration->change-event updated)
                         {:event                    "RELOCATE"
                          :author_type              "CLERK"
                          :created_by               (get-in session [:identity :oid])
                          :original_exam_session_id exam-session-id}))
                updated)
              false))))))
  (cancel-registration!
    [{:keys [spec]} session registration-id]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        (fn do-cancel! []
          (when-let [canceled (q/cancel-registration<! tx {:id registration-id})]
            (q/insert-registration-change-event!
              tx
              (merge (registration->change-event canceled)
                     {:event       "CANCEL"
                      :author_type "CLERK"
                      :created_by  (get-in session [:identity :oid])}))
            canceled)))))
  (update-exam-session!
    [{:keys [spec]} oid id exam-session]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        #(do
           (q/delete-exam-session-location! tx {:id id})
           (doseq [location (:location exam-session)]
             (q/insert-exam-session-location! tx (assoc location :exam_session_id id)))
           (add-and-link-contact tx spec oid id (:contact exam-session))
           (let [updated (int->boolean (q/update-exam-session!
                                         tx
                                         (merge {:office_oid nil} (assoc (convert-dates exam-session) :oid oid :id id))))]
             updated)))))
  (delete-exam-session! [{:keys [spec]} id oid send-to-queue-fn]
    (jdbc/with-db-transaction [tx spec]
      (rollback-on-exception
        tx
        (fn []
          (q/delete-exam-session-contact-by-session-id! tx {:exam_session_id id})
          (q/delete-participant-sync-status! tx {:exam_session_id id})
          (let [deleted (int->boolean (q/delete-exam-session! tx {:id id :oid oid}))]
            (when deleted
              (send-to-queue-fn))
            deleted)))))
  (get-exam-session-with-location [{:keys [spec]} id lang]
    (first (q/select-exam-session-with-location spec {:id id :lang lang})))
  (get-exam-session-by-id [{:keys [spec]} id]
    (first (q/select-exam-session-by-id spec {:id id})))
  (get-exam-session-registration-by-registration-id [{:keys [spec]} registration-id]
    (first (q/select-exam-session-registration-by-registration-id spec {:registration_id registration-id})))
  (get-exam-sessions-to-be-synced [{:keys [spec]} retry-duration]
    (q/select-exam-sessions-to-be-synced spec {:duration retry-duration}))
  (get-exam-session-participants [{:keys [spec]} id oid]
    (q/select-exam-session-participants spec {:id id :oid oid}))
  (get-completed-exam-session-participants [{:keys [spec]} id]
    (q/select-completed-exam-session-participants spec {:id id}))
  (get-exam-sessions [{:keys [spec]} from]
    (q/select-exam-sessions spec {:from from}))
  (get-exam-sessions-for-oid [{:keys [spec]} oid from]
    (jdbc/with-db-transaction [tx spec]
      (let [exam-sessions (q/select-exam-sessions-for-oid tx {:oid  oid
                                                              :from from})]
        (mapv (fn [{date :session_date id :id :as session}]
                (assoc session :transfer_targets (get-transfer-targets-for-exam-session tx date id)))
              exam-sessions))))
  (remove-old-entries-from-exam-session-queue! [{:keys [spec]}]
    (q/delete-exam-session-queue-entries-for-old-exam-dates! spec))
  (get-contact-info-by-exam-session-id
    [{:keys [spec]} id]
    (first (q/select-exam-session-contact-info spec {:id id})))
  (get-exam-session-location-extra-information
    [{:keys [spec]} id lang]
    (first (q/select-exam-session-extra-information spec {:id   id
                                                          :lang lang})))
  (get-exam-sessions-for-statistics-sync [{:keys [spec]}]
    (q/select-exam-sessions-for-statistics-sync spec))
  (get-exam-session-statistics [{:keys [spec]} statistics-id]
    (first (q/select-exam-session-statistics spec {:id statistics-id})))
  (get-initial-statistics-for-exam-session [{:keys [spec]} exam-session-id]
    (first (q/select-initial-statistics-for-exam-session spec {:id exam-session-id})))
  (get-unprocessed-events-for-exam-session [{:keys [spec]} exam-session-id last-processed-event-id]
    (q/select-unprocessed-change-events-for-exam-session
      spec
      {:exam_session_id exam-session-id :last_processed_event_id last-processed-event-id}))
  (update-exam-session-statistics! [{:keys [spec]} statistics]
    (q/insert-exam-session-statistics! spec statistics)))
