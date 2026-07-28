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
    [yki.registration.registration :refer [send-lifted-from-queue-email! send-lifted-from-queue-for-free-email!]]
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
                               :task      "MIGRATE_PERSON_HANDLER"
                               :interval  "59 SECONDS"})

(defonce registration-queue-handler-conf {:worker-id (str (random-uuid))
                                          :task      "REGISTRATION_QUEUE_HANDLER"
                                          :interval  "29 SECONDS"})

(defonce exam-session-statistics-handler-conf {:worker-id (str (random-uuid))
                                               :task      "EXAM_SESSION_STATISTICS_HANDLER"
                                               :interval  "57 MINUTES"})

(defonce exam-session-solki-sync-handler-conf {:worker-id (str (random-uuid))
                                               :task      "EXAM_SESSION_SOLKI_SYNC_HANDLER"
                                               :interval  "59 MINUTES"})

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
       (log/error e "Registration state handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Registration state handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::participants-sync-handler
  [_ {:keys [db url-helper onr-client basic-auth disabled retry-duration-in-days]}]
  {:pre [(some? db) (some? url-helper) (some? onr-client) (some? basic-auth) (some? retry-duration-in-days)]}
  #(try
     (when (job-db/try-to-acquire-lock! db participants-sync-handler-conf)
       (log/info "Check participants sync")
       (let [exam-sessions (exam-session-db/get-exam-sessions-to-be-synced db (str retry-duration-in-days " days"))]
         (log/info "Synchronizing participants of exam sessions" exam-sessions)
         (doseq [exam-session exam-sessions]
           (try
             (yki-register/sync-exam-session-participants db url-helper onr-client basic-auth disabled (:exam_session_id exam-session))
             (catch Exception e
               (do
                 (log/error e "Failed to synchronize participants of exam session" exam-session)
                 (exam-session-db/set-participants-sync-to-failed! db (:exam_session_id exam-session) (str retry-duration-in-days " days"))))))))
     (catch Exception e
       (log/error e "Participant sync handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Participant sync handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::persons-sync-handler
  [_ {:keys [db url-helper basic-auth disabled retry-duration-in-days]}]
  {:pre [(some? db) (some? url-helper) (some? basic-auth) (some? retry-duration-in-days)]}
  #(try
     (when (job-db/try-to-acquire-lock! db persons-sync-handler-conf)
       (let [persons-to-sync (person-db/get-persons-to-sync db (str retry-duration-in-days " days"))]
         (doseq [{:keys [id person_oid]} persons-to-sync]
           (try
             (let [person          (person-db/get-full-person-details db person_oid)
                   solki-response  (yki-register/sync-person url-helper basic-auth disabled person)
                   status          (:status solki-response)
                   success?        (= 200 status)
                   retry-if-error? (and (not success?)
                                        (not (= 404 status)))]
               (when (not success?)
                 (log/error "Updating person details to Solki failed!" {:oid person_oid, :response solki-response}))
               (person-db/mark-person-sync-attempt! db id success? retry-if-error?))
             (catch Exception e
               (do
                 (log/error e "Updating person details to Solki failed!" {:id id, :oid person_oid})
                 (person-db/mark-person-sync-attempt! db id false true)))))))
     (catch Exception e
       (log/error e "Persons sync handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Persons sync handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

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
       (log/error e "Email queue reader failed"))
     (catch Throwable t
       (log/error t "Email queue reader failed with a non-Exception Throwable"))))

(defmethod ig/init-key ::data-sync-queue-reader
  [_ {:keys [data-sync-q url-helper db retry-duration-in-days disabled basic-auth]}]
  {:pre [(some? url-helper) (some? data-sync-q) (some? db) (some? retry-duration-in-days) (some? basic-auth)]}
  #(try
     (take-with-error-handling data-sync-q retry-duration-in-days
                               (fn [data-sync-req]
                                 (log/info "Received request to sync data to yki register" data-sync-req)
                                 (yki-register/sync-exam-session-and-organizer db url-helper basic-auth disabled data-sync-req)))
     (catch Exception e
       (log/error e "Data sync queue reader failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Data sync queue reader failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

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
       (log/error e "Old data removal failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Old data removal failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::sync-participant-onr-data-handler [_ {:keys [db onr-client]}]
  {:pre [(some? db)]}
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
       (log/error e "Syncing participant ONR data failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Syncing participant ONR data failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::registration-queue-handler [_ {:keys [db url-helper payment-helper email-q]}]
  {:pre [(some? db) (some? url-helper) (some? payment-helper) (some? email-q)]}
  #(try
     (when (job-db/try-to-acquire-lock! db registration-queue-handler-conf)
       (log/info "Registration queue handler started")
       (let [positive-types (fn [counts] (keep (fn [[t n]] (when (pos? n) t)) counts))
             create-and-send-payment-link! (fn [tx {:keys [id participant_id ui_language]}]
                                             (let [lang                (or ui_language "fi")
                                                   email-template-data (registration-db/get-registration-data-with-tx tx id participant_id lang)
                                                   code                (str (random-uuid))
                                                   login-url           (url-helper :yki.login-link.url code)
                                                   free?               (:free_registration_id email-template-data)]
                                               (if free?
                                                 (send-lifted-from-queue-for-free-email! url-helper email-q lang email-template-data)
                                                 (send-lifted-from-queue-email! db url-helper payment-helper email-q lang email-template-data code login-url))))
             exam-session-details          (registration-db/get-participant-and-queue-count-for-ongoing-admissions db)]
         (doseq [{:keys [exam_session_id type
                         max_participants max_participants_read_listen max_participants_speak_write
                         participants participants_read_listen participants_speak_write
                         queue queue_read_listen queue_speak_write]} exam-session-details
                 ;; An ADMISSION may be ALL_PARTS (occupying both pools), but a partial-exam QUEUE
                 ;; entry is always for one specific subexam, so we lift per subexam pool.
                 :let [to-lift (if (= type "FULL")
                                 {"ALL_PARTS" (min queue (- max_participants participants))}
                                 (let [places-read-listen (- max_participants_read_listen participants_read_listen)
                                       places-speak-write (- max_participants_speak_write participants_speak_write)]
                                   ;; for example: {"READ" 10 "WRITE" 0}
                                   {(if (= type "READ_SPEAK") "READ" "LISTEN") (min queue_read_listen places-read-listen)
                                    (if (= type "LISTEN_WRITE") "WRITE" "SPEAK") (min queue_speak_write places-speak-write)}))]]
           (try
             (loop [counts to-lift]
               (let [types (positive-types counts)]
                 (when (seq types)
                   (when-let [lifted-type (:partial_exam_type
                                            (registration-db/lift-registration-from-queue! db exam_session_id create-and-send-payment-link! types))]
                     (recur (update counts lifted-type dec))))))
             (catch Exception e
               (log/error e "Registration queue handler failed for exam session" exam_session_id "[ERROR_SCHEDULED_TASK]"))))))
     (catch Exception e
       (log/error e "Registration queue handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Registration queue handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::migrate-person-handler [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db person-migrator-conf)
       (let [persons       (person-db/get-persons-without-gender-or-nationality db)
             persons-count (count persons)]
         (when (pos-int? persons-count)
           (log/info "Updating data for" persons-count "person entries"))
         (doseq [{:keys [oid gender ssn nationalities] :as person} persons]
           (try
             (let [gender      (yki-register/convert-gender gender ssn)
                   nationality (first nationalities)]
               (person-db/update-person-gender-and-nationality!
                 db
                 {:oid              oid
                  :nationality_code nationality
                  :gender           gender}))
             (catch Exception e
               (log/error e "Updating gender and nationality failed for person" (dissoc person :ssn)))))))
     (catch Exception e
       (log/error e "Person migration failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Person migration failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defn- statistics+event->statistics [{:keys [exam_session_id participants queue max_participant_count max_queue_count max_participants_at max_queue_at]
                                      :as   statistics}
                                     {:keys [id created_at event registration_kind]
                                      :as   change-event}]
  (try
    (let [queue?                   (= registration_kind "QUEUE")
          update-participants      (if queue?
                                     identity
                                     (case event
                                       ("CREATE" "LIFT_FROM_QUEUE")
                                       inc
                                       ("CANCEL" "EXPIRE")
                                       dec
                                       "RELOCATE"
                                       (if (= exam_session_id (:exam_session_id change-event))
                                         inc dec)
                                       ("SUBMIT" "COMPLETE_PAYMENT")
                                       identity))
          update-queue             (if queue?
                                     (case event
                                       "CREATE"
                                       inc
                                       ("CANCEL" "EXPIRE")
                                       dec
                                       "SUBMIT"
                                       identity)
                                     (case event
                                       "LIFT_FROM_QUEUE"
                                       dec
                                       identity))
          new-participants         (update-participants participants)
          new-queue                (update-queue queue)
          has-new-max-participants (< max_participant_count new-participants)
          has-new-max-queue        (< max_queue_count new-queue)]
      {:exam_session_id         exam_session_id
       :last_processed_event_id id
       :max_participants_at     (if has-new-max-participants created_at max_participants_at)
       :max_queue_at            (if has-new-max-queue created_at max_queue_at)
       :participants            new-participants
       :queue                   new-queue
       :max_participant_count   (if has-new-max-participants new-participants max_participant_count)
       :max_queue_count         (if has-new-max-queue new-queue max_queue_count)})
    (catch Exception e
      (log/error e "Caught error while processing change event; ignoring change event, potentially distorting statistics! Change event id:" id)
      statistics)))

(defn- get-statistics-entry [db {:keys [id last_processed_event_id previous_statistics_id]}]
  (if (some? previous_statistics_id)
    (let [previous-statistics (exam-session-db/get-exam-session-statistics db previous_statistics_id)
          new-events          (exam-session-db/get-unprocessed-events-for-exam-session db id last_processed_event_id)]
      (if (seq new-events)
        (reduce statistics+event->statistics previous-statistics new-events)
        nil))
    (let [{:keys [participants queue]} (exam-session-db/get-initial-statistics-for-exam-session db id)
          now (t/now)]
      {:exam_session_id         id
       :last_processed_event_id nil
       :max_participants_at     now
       :max_queue_at            now
       :participants            participants
       :max_participant_count   participants
       :queue                   queue
       :max_queue_count         queue})))

(comment
  (let [now           (t/now)
        initial-state {:exam_session_id         1
                       :last_processed_event_id nil
                       :max_participants_at     now
                       :max_queue_at            now
                       :participants            3
                       :max_participant_count   3
                       :queue                   1
                       :max_queue_count         1}
        events        [{:exam_session_id   2
                        :event             "RELOCATE"
                        :registration_kind "ADMISSION"
                        :created_at        (t/plus now (t/minutes 1))
                        :id 3}
                       {:exam_session_id   1
                        :event             "CREATE"
                        :registration_kind "QUEUE"
                        :created_at        (t/plus now (t/minutes 2))
                        :id 5}
                       {:exam_session_id   1
                        :event             "LIFT_FROM_QUEUE"
                        :registration_kind "ADMISSION"
                        :created_at        (t/plus now (t/minutes 3))
                        :id 9}]]
    (reduce statistics+event->statistics initial-state events)))

(defmethod ig/init-key ::exam-session-statistics-handler [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db exam-session-statistics-handler-conf)
       (log/info "Exam session statistics handler started")
       (let [exam-sessions-to-sync (exam-session-db/get-exam-sessions-for-statistics-sync db)]
         (log/info "Found exam sessions to sync" (map :id exam-sessions-to-sync))
         (doseq [exam-session exam-sessions-to-sync]
           (when-let [statistics-to-insert (get-statistics-entry db exam-session)]
             (exam-session-db/update-exam-session-statistics! db statistics-to-insert)))))
     (catch Exception e
       (log/error e "Exam session statistics handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Exam session statistics handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))

(defmethod ig/init-key ::exam-session-solki-sync-handler
  [_ {:keys [db url-helper basic-auth disabled]}]
  {:pre [(some? db) (some? url-helper) (some? basic-auth)]}
  #(try
     (when (job-db/try-to-acquire-lock! db exam-session-solki-sync-handler-conf)
       (log/info "Exam session Solki sync handler started")
       (let [unsynced-sessions (exam-session-db/get-unsynced-exam-sessions db)]
         (log/info "Found unsynced exam sessions" (map :id unsynced-sessions))
         (doseq [exam-session unsynced-sessions]
           (try
             (yki-register/sync-exam-session-and-organizer db url-helper basic-auth disabled
                                                           {:type         "CREATE"
                                                            :exam-session exam-session})
             (exam-session-db/set-exam-session-synced! db (:id exam-session))
             (catch Exception e
               (log/error e "Failed to sync exam session to Solki" {:id (:id exam-session)}))))))
     (catch Exception e
       (log/error e "Exam session Solki sync handler failed [ERROR_SCHEDULED_TASK]"))
     (catch Throwable t
       (log/error t "Exam session Solki sync handler failed with a non-Exception Throwable [ERROR_SCHEDULED_TASK]"))))
