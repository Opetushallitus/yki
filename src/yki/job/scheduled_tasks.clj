(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :as log]
            [yki.boundary.email :as email]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.yki-register :as yki-register]
            [yki.util.template-util :as template-util]
            [yki.job.job-queue]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce registration-state-handler-conf {:worker-id (str (UUID/randomUUID))
                                          :task "REGISTRATION_STATE_HANDLER"
                                          :interval "59 SECONDS"})

(defonce participants-sync-handler-conf {:worker-id (str (UUID/randomUUID))
                                         :task "PARTICIPANTS_SYNC_HANDLER"
                                         :interval "479 MINUTES"})

(defonce exam-session-queue-handler-conf {:worker-id (str (UUID/randomUUID))
                                          :task "EXAM_SESSION_QUEUE_HANDLER"
                                          :interval "599 SECONDS"})

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

(defmethod ig/init-key :yki.job.scheduled-tasks/registration-state-handler
  [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (job-db/try-to-acquire-lock! db registration-state-handler-conf)
       (log/debug "Check started registrations expiry")
       (let [updated (registration-db/update-started-registrations-to-expired! db)]
         (when updated (log/info "Started registrations set to expired" updated)))
       (log/debug "Check submitted registrations expiry")
       (let [updated (registration-db/update-submitted-registrations-to-expired! db)]
         (when updated (log/info "Submitted registrations set to expired" updated))))
     (catch Exception e
       (log/error e "Registration state handler failed"))))

(defmethod ig/init-key :yki.job.scheduled-tasks/participants-sync-handler
  [_ {:keys [db url-helper basic-auth disabled retry-duration-in-days]}]
  {:pre [(some? db) (some? url-helper) (some? basic-auth) (some? retry-duration-in-days)]}
  #(try
     (when (job-db/try-to-acquire-lock! db participants-sync-handler-conf)
       (log/info "Check participants sync")
       (let [exam-sessions (exam-session-db/get-exam-sessions-to-be-synced db (str retry-duration-in-days " days"))]
         (log/info "Syncronizing participants of exam sessions" exam-sessions)
         (doseq [exam-session exam-sessions]
           (try
             (yki-register/sync-exam-session-participants db url-helper basic-auth disabled (:exam_session_id exam-session))
             (catch Exception e
               (do
                 (log/error e "Failed to syncronize participants of exam session" exam-session)
                 (exam-session-db/set-participants-sync-to-failed! db (:exam_session_id exam-session) (str retry-duration-in-days " days"))))))))
     (catch Exception e
       (log/error e "Participant sync handler failed"))))

(defmethod ig/init-key :yki.job.scheduled-tasks/email-queue-reader
  [_ {:keys [email-q handle-at-once-at-most url-helper retry-duration-in-days disabled]}]
  {:pre [(some? url-helper) (pos-int? handle-at-once-at-most) (some? email-q) (some? retry-duration-in-days)]}
  #(doseq [_ (range (min handle-at-once-at-most (pgq/count email-q)))]
     (take-with-error-handling email-q retry-duration-in-days
                               (fn [email-req]
                                 (log/info "Email queue reader sending email to:" (:recipients email-req))
                                 (email/send-email url-helper email-req disabled)))))

(defmethod ig/init-key :yki.job.scheduled-tasks/data-sync-queue-reader
  [_ {:keys [data-sync-q url-helper db retry-duration-in-days disabled basic-auth]}]
  {:pre [(some? url-helper) (some? data-sync-q) (some? db) (some? retry-duration-in-days) (some? basic-auth)]}
  #(take-with-error-handling data-sync-q retry-duration-in-days
                             (fn [data-sync-req]
                               (log/info "Received request to sync data to yki register" data-sync-req)
                               (yki-register/sync-exam-session-and-organizer db url-helper basic-auth disabled data-sync-req))))

(defmethod ig/init-key :yki.job.scheduled-tasks/exam-session-queue-handler
  [_ {:keys [db email-q url-helper]}]
  {:pre [(some? db) (some? email-q) (some? url-helper)]}
  #(try
     (when (job-db/try-to-acquire-lock! db exam-session-queue-handler-conf)
       (log/info "Exam session queue handler started")
       (let [exam-sessions-with-queue (exam-session-db/get-exam-sessions-with-queue db)]
         (doseq [exam-session exam-sessions-with-queue]
           (log/info "Exam session with queue and free space" exam-session)
           (try
             (doseq [item (:queue exam-session)]
               (let [lang             (:lang item)
                     email            (:email item)
                     exam-session-id  (:exam_session_id exam-session)
                     exam-session-url (url-helper :exam-session.url exam-session-id lang)
                     language         (template-util/get-language url-helper (:language_code exam-session) lang)
                     level            (template-util/get-level url-helper (:level_code exam-session) lang)]
                 (log/info "Sending notification to email" email)
                 (pgq/put email-q
                          {:recipients [email]
                           :created (System/currentTimeMillis)
                           :subject (template-util/subject url-helper "queue_notification" lang exam-session)
                           :body (template-util/render url-helper "queue_notification"
                                                       lang
                                                       (assoc exam-session
                                                              :exam-session-url exam-session-url
                                                              :language language
                                                              :level level))})
                 (exam-session-db/update-exam-session-queue-last-notified-at! db email exam-session-id)))
             (catch Exception e
               (log/error e "Failed to send notifications for" exam-session))))))
     (catch Exception e
       (log/error e "Exam session queue handler failed"))))

