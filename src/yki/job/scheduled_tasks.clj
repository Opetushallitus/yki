(ns yki.job.scheduled-tasks
  (:require
    [clj-time.coerce :as c]
    [clj-time.core :as t]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [pgqueue.core :as pgq]
    [yki.boundary.cas-ticket-db :as cas-ticket-db]
    [yki.boundary.debug :as debug]
    [yki.boundary.email :as email]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.job-db :as job-db]
    [yki.boundary.onr :as onr]
    [yki.boundary.person-db :as person-db]
    [yki.boundary.registration-db :as registration-db]
    [yki.boundary.yki-register :as yki-register]
    [yki.registration.registration :refer [send-lifted-from-queue-email!]]
    [yki.job.job-queue]))

(defonce registration-state-handler-conf {:worker-id (str (random-uuid))
                                          :task      "REGISTRATION_STATE_HANDLER"
                                          :interval  "59 SECONDS"})

(defonce participants-sync-handler-conf {:worker-id (str (random-uuid))
                                         :task      "PARTICIPANTS_SYNC_HANDLER"
                                         :interval  "59 MINUTES"})

(defonce persons-sync-handler-conf {:worker-id (str (random-uuid))
                                    :task      "PERSONS_SYNC_HANDLER"
                                    :interval  "179 SECONDS"})

(defonce remove-old-data-handler-conf {:worker-id (str (random-uuid))
                                       :task      "REMOVE_OLD_DATA_HANDLER"
                                       :interval  "1 DAY"})

(defonce sync-onr-participant-data-handler-conf {:worker-id (str (random-uuid))
                                                 :task      "SYNC_ONR_PARTICIPANT_DATA_HANDLER"
                                                 :interval  "59 MINUTES"})

(defonce person-migrator-conf {:worker-id (str (random-uuid))
                               :task "MIGRATE_PERSON_HANDLER"
                               :interval "59 SECONDS"})

(defonce registration-queue-handler-conf {:worker-id (str (random-uuid))
                                          :task      "REGISTRATION_QUEUE_HANDLER"
                                          :interval  "29 SECONDS"})

(defn- take-with-error-handling
  "Takes message from queue and executes handler function with message.
  Rethrows exceptions if retry until limit is not reached so that message is not
  removed from queue and can be processed again."
  [queue retry-duration-in-days handler-fn]
  (log/debug "Executing handler on queue" (:name queue))
  (try
    (pgq/take-with
      [request queue]
      (when request
        (try
          (handler-fn request)
          (catch Exception e
            (let [created     (c/from-long (:created request))
                  retry-until (t/plus created (t/days retry-duration-in-days))]
              (if (t/after? (t/now) retry-until)
                (log/error "Stopped retrying" request)
                (throw e)))))))
    (catch Exception e
      (log/error e "Queue reader failed"))))

(defmethod ig/init-key ::registration-state-handler
  [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db registration-state-handler-conf)
       (log/debug "Check started registrations expiry")
       (let [ids (registration-db/update-started-registrations-to-expired! db)]
         (when ids (log/info "Started registrations set to expired" ids)))
       (log/debug "Check submitted registrations expiry")
       (let [ids (registration-db/update-submitted-registrations-to-expired! db)]
         (when ids (log/info "Submitted registrations set to expired" ids)))
       (let [ids (registration-db/expire-queued-registrations-after-exam-date! db)]
         (when ids (log/info "Queued registrations set to expired" ids))))
     (catch Exception e
       (log/error e "Registration state handler failed"))))

(defmethod ig/init-key ::participants-sync-handler
  [_ {:keys [db url-helper basic-auth disabled retry-duration-in-days]}]
  {:pre [(some? db) (some? url-helper) (some? basic-auth) (some? retry-duration-in-days)]}
  #(try
     (when (job-db/try-to-acquire-lock! db participants-sync-handler-conf)
       (log/info "Check participants sync")
       (let [exam-sessions (exam-session-db/get-exam-sessions-to-be-synced db (str retry-duration-in-days " days"))]
         (log/info "Synchronizing participants of exam sessions" exam-sessions)
         (doseq [exam-session exam-sessions]
           (try
             (yki-register/sync-exam-session-participants db url-helper basic-auth disabled (:exam_session_id exam-session))
             (catch Exception e
               (do
                 (log/error e "Failed to synchronize participants of exam session" exam-session)
                 (exam-session-db/set-participants-sync-to-failed! db (:exam_session_id exam-session) (str retry-duration-in-days " days"))))))))
     (catch Exception e
       (log/error e "Participant sync handler failed"))))

(defmethod ig/init-key ::persons-sync-handler
  [_ {:keys [db url-helper basic-auth disabled retry-duration-in-days]}]
  {:pre [(some? db) (some? url-helper) (some? basic-auth) (some? retry-duration-in-days)]}
  #(try
     (when (job-db/try-to-acquire-lock! db persons-sync-handler-conf)
       (let [persons-to-sync (person-db/get-persons-to-sync db (str retry-duration-in-days " days"))]
         (doseq [{:keys [id person_oid]} persons-to-sync]
           (try
             (let [person (person-db/get-person db person_oid)]
               (yki-register/sync-person url-helper basic-auth disabled person)
               (person-db/mark-person-sync-attempt! db id true))
             (catch Exception e
               (do
                 (log/error e "Updating person details to Solki failed!" {:id id, :oid person_oid})
                 (person-db/mark-person-sync-attempt! db id false)))))))
     (catch Exception e
       (log/error e "Persons sync handler failed"))))

(defmethod ig/init-key ::email-queue-reader
  [_ {:keys [email-q handle-at-once-at-most url-helper retry-duration-in-days disabled]}]
  {:pre [(some? url-helper) (pos-int? handle-at-once-at-most) (some? email-q) (some? retry-duration-in-days)]}
  #(try
     (doseq [_ (range (min handle-at-once-at-most (pgq/count email-q)))]
       (take-with-error-handling email-q retry-duration-in-days
                                 (fn [email-req]
                                   (log/info "Email queue reader sending email to:" (:recipients email-req))
                                   (email/send-email! url-helper email-req disabled))))
     (catch Exception e
       (log/error e "Email queue reader failed"))))

(defmethod ig/init-key ::data-sync-queue-reader
  [_ {:keys [data-sync-q url-helper db retry-duration-in-days disabled basic-auth]}]
  {:pre [(some? url-helper) (some? data-sync-q) (some? db) (some? retry-duration-in-days) (some? basic-auth)]}
  #(take-with-error-handling data-sync-q retry-duration-in-days
                             (fn [data-sync-req]
                               (log/info "Received request to sync data to yki register" data-sync-req)
                               (yki-register/sync-exam-session-and-organizer db url-helper basic-auth disabled data-sync-req))))

(defmethod ig/init-key ::remove-old-data-handler
  [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db remove-old-data-handler-conf)
       (log/info "Old data removal started")
       (let [deleted-from-exam-session-queue (exam-session-db/remove-old-entries-from-exam-session-queue! db)
             deleted-cas-tickets             (cas-ticket-db/delete-old-tickets! db :virkailija)
             deleted-cas-oppija-tickets      (cas-ticket-db/delete-old-tickets! db :oppija)]
         (log/info "Removed old entries from exam-session-queue:" deleted-from-exam-session-queue)
         (log/info "Removed old CAS tickets:" deleted-cas-tickets)
         (log/info "Removed old CAS-oppija tickets:" deleted-cas-oppija-tickets)))
     (catch Exception e
       (log/error e "Old data removal failed"))))

(defmethod ig/init-key ::sync-participant-onr-data-handler [_ {:keys [db onr-client]}]
  {:pre [(some? db) (some? onr-client)]}
  #(try
     (when (job-db/try-to-acquire-lock! db sync-onr-participant-data-handler-conf)
       (log/info "Participant ONR data syncing started")
       (let [participants-to-sync (debug/get-participants-for-onr-check db)
             batch-size           1000]
         (doseq [participants-batch (partition-all batch-size participants-to-sync)]
           (let [oid->participant (into {} (map (juxt :person_oid identity)) participants-batch)
                 onr-data         (->> participants-batch
                                       (map :person_oid)
                                       (onr/list-persons-by-oids onr-client))]
             (doseq [onr-entry onr-data]
               (let [onr-details {:person_oid        (onr-entry "oidHenkilo")
                                  :oppijanumero      (onr-entry "oppijanumero")
                                  :is_individualized (or (onr-entry "yksiloity")
                                                         (onr-entry "yksiloityVTJ"))}
                     oid         (:person_oid onr-details)]
                 (debug/upsert-participant-onr-data!
                   db
                   (merge
                     (oid->participant oid)
                     onr-details)))))
           ; Wait 10 seconds between calls to ONR just to play it safe.
           (Thread/sleep 10000))))
     (catch Exception e
       (log/error e "Syncing participant ONR data failed"))))

(defmethod ig/init-key ::registration-queue-handler [_ {:keys [db url-helper payment-helper email-q]}]
  {:pre [(some? db) (some? url-helper) (some? payment-helper) (some? email-q)]}
  #(try
     (when (job-db/try-to-acquire-lock! db registration-queue-handler-conf)
       (log/info "Registration queue handler started")
       (let [create-and-send-payment-link! (fn [{:keys [id participant_id ui_language]}]
                                             (let [lang                (or ui_language "fi")
                                                   email-template-data (registration-db/get-registration-data db id participant_id lang)
                                                   code                (str (random-uuid))
                                                   login-url           (url-helper :yki.login-link.url code)]
                                               (send-lifted-from-queue-email! db url-helper payment-helper email-q lang email-template-data code login-url)))
             exam-session-details          (registration-db/get-participant-and-queue-count-for-ongoing-admissions db)]
         (doseq [{:keys [exam_session_id max_participants participants queue]} exam-session-details
                 :let [available-places (- max_participants participants)
                       to-lift          (min queue available-places)]
                 _ (range 0 to-lift)]
           (registration-db/lift-registration-from-queue! db exam_session_id create-and-send-payment-link!))))
     (catch Exception e
       (log/error e "Registration queue handler failed"))))

(defmethod ig/init-key ::migrate-person-handler [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db person-migrator-conf)
       (log/info "Populating gender and nationality values for persons from past registrations")
       ; TODO
       ; 1. Get persons without gender / nationality
       ; 2. Get latest registrations for those persons with the gender and nationality information
       ; 3. Read declared nationality, gender and SSN from form
       ; 4. Deduce gender using SSN if given and otherwise what's entered on form
       ; 5. Update gender & nationality
       ; 6. Rinse and repeat for next batch
       (let [persons (person-db/get-persons-without-gender-or-nationality db)]
         (log/info "Updating data for" (count persons) "person entries")
         (doseq [])))
     (catch Exception e
       (log/error e "Person migration failed"))))

(comment
  (let [[_ db] (ig/find-derived-1 (local/current-state) :duct.database/sql)]
    (person-db/get-persons-without-gender-or-nationality db)))
