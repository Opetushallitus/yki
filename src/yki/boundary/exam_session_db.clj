(ns yki.boundary.exam-session-db
  (:require [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [duct.database.sql]
            [jeesql.core :refer [require-sql]]
            [yki.boundary.db-extensions]
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
   for now only one is handled."
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

(defprotocol ExamSessions
  (create-exam-session! [db oid exam-session send-to-queue-fn])
  (update-exam-session! [db oid id exam-session])
  (delete-exam-session! [db id oid send-to-queue-fn])
  (init-participants-sync-status! [db exam-session-id])
  (init-relocated-participants-sync-status! [db exam-session-id])
  (set-participants-sync-to-success! [db exam-session-id])
  (set-participants-sync-to-failed! [db exam-session-id retry-duration])
  (cancel-registration! [db registration-id])
  (cancel-unpaid-registration! [db registration-id oid])
  (update-registration-exam-session! [db to-exam-session-id registration-id oid])
  (get-exam-session-by-id [db id])
  (get-exam-session-registration-by-registration-id [db registration-id])
  (get-exam-session-with-location [db id lang])
  (get-exam-session-participants [db id oid])
  (get-completed-exam-session-participants [db id])
  (get-exam-sessions-to-be-synced [db retry-duration])
  (get-exam-sessions [db oid from]
    "Get exam sessions by optional oid and from arguments")
  (get-exam-sessions-with-queue [db])
  (get-email-added-to-queue? [db email exam-session-id])
  (add-to-exam-session-queue! [db email lang exam-session-id])
  (update-exam-session-queue-last-notified-at! [db email exam-session-id])
  (remove-from-exam-session-queue! [db email exam-session-id])
  (set-post-admission-active! [db id quota])
  (set-post-admission-deactive! [db id])
  (get-contact-info-by-exam-session-id [db id])
  (get-exam-session-location-extra-information [db id lang]))

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
    [{:keys [spec]} to-exam-session-id registration-id oid]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/update-registration-exam-session! tx {:exam_session_id to-exam-session-id
                                                             :registration_id registration-id
                                                             :oid             oid}))))
  (cancel-registration!
    [{:keys [spec]} registration-id]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/cancel-registration! tx {:id registration-id}))))
  (cancel-unpaid-registration!
    [{:keys [spec]} registration-id oid]
    (jdbc/with-db-transaction [tx spec]
      (int->boolean (q/cancel-unpaid-registration-for-organizer! tx {:id registration-id :oid oid}))))
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
          (q/delete-from-exam-session-queue-by-session-id! tx {:exam_session_id id})
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
  (get-exam-sessions [{:keys [spec]} oid from]
    (q/select-exam-sessions spec {:oid  oid
                                  :from from}))
  (get-email-added-to-queue? [{:keys [spec]} email exam-session-id]
    (int->boolean (:count (first (q/select-email-added-to-queue spec {:email           email
                                                                      :exam_session_id exam-session-id})))))
  (get-exam-sessions-with-queue [{:keys [spec]}]
    (q/select-exam-sessions-with-queue spec))
  (add-to-exam-session-queue!
    [{:keys [spec] :as db} email lang exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (let [registration-not-open? (-> (q/select-exam-session-registration-open spec {:exam_session_id exam-session-id})
                                       (first)
                                       (:exists)
                                       (not))
            already-in-queue?      (get-email-added-to-queue? db email exam-session-id)
            queue-size             (-> (q/select-exam-session-queue-count spec {:exam_session_id exam-session-id})
                                       (first)
                                       (:count))
            full-queue?            (<= 50 queue-size)]
        (if (or registration-not-open?
                already-in-queue?
                full-queue?)
          {:exists  already-in-queue?
           :full    full-queue?
           :success false}
          (do
            (q/insert-exam-session-queue! tx {:exam_session_id exam-session-id
                                              :lang            lang
                                              :email           email})
            {:success true})))))
  (update-exam-session-queue-last-notified-at!
    [{:keys [spec]} email exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/update-exam-session-queue-last-notified-at! tx {:exam_session_id exam-session-id
                                                         :email           email})))
  (remove-from-exam-session-queue!
    [{:keys [spec]} email exam-session-id]
    (jdbc/with-db-transaction [tx spec]
      (q/delete-from-exam-session-queue! tx {:exam_session_id exam-session-id
                                             :email           email})))

  (set-post-admission-active!
    [{:keys [spec]} id quota]
    (jdbc/with-db-transaction [tx spec]
      (q/activate-exam-session-post-admission! tx {:exam_session_id      id
                                                   :post_admission_quota quota})))

  (set-post-admission-deactive!
    [{:keys [spec]} id]
    (jdbc/with-db-transaction [tx spec]
      (q/deactivate-exam-session-post-admission! tx {:exam_session_id id})))

  (get-contact-info-by-exam-session-id
    [{:keys [spec]} id]
    (first (q/select-exam-session-contact-info spec {:id id})))

  (get-exam-session-location-extra-information
    [{:keys [spec]} id lang]
    (first (q/select-exam-session-extra-information spec {:id   id
                                                          :lang lang}))))
