(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :as log]
            [yki.boundary.email :as email]
            [yki.boundary.registration-db :as registration-db]
            [yki.sync.yki-register :as yki-register]
            [yki.job.job-queue]
            [clj-time.local :as l]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defn handle-registration-state-changes [])

(defmethod ig/init-key :yki.job.scheduled-tasks/registration-state-handler
  [_ {:keys [db]}]
  {:pre [(some? db)]}
  #(try
     (when (= (job-db/try-to-acquire-lock! db worker-id "REGISTRATION_STATE_HANDLER" "599 SECONDS") 1)
       (log/info "Check started registrations expiry")
       (let [updated (registration-db/update-started-registrations-to-expired! db)]
         (log/info "Started registrations set to expired" updated))
       (log/info "Check submitted registrations expiry")
       (let [updated (registration-db/update-submitted-registrations-to-expired! db)]
         (log/info "Submitted registrations set to expired" updated)))
     (catch Exception e
       (log/error e "Registration state handler failed"))))

(defmethod ig/init-key :yki.job.scheduled-tasks/email-queue-reader
  [_ {:keys [email-q url-helper]}]
  {:pre [(some? url-helper) (some? email-q)]}
  #(try
     (pgq/take-with
      [email-req email-q]
      (when email-req
        (log/info "Email queue reader sending email to:" (:recipients email-req))
        (email/send-email url-helper email-req)))
     (catch Exception e
       (log/error e "Email queue reader failed"))))

(defmethod ig/init-key :yki.job.scheduled-tasks/exam-session-queue-reader
  [_ {:keys [exam-session-q url-helper db]}]
  {:pre [(some? url-helper) (some? exam-session-q) (some? db)]}
  #(try
     (pgq/take-with
      [exam-session-req exam-session-q]
      (when exam-session-req
        (log/info "Received request to sync exam session" exam-session-req)
        (yki-register/sync-exam-session-and-organizer db url-helper (:exam-session-id exam-session-req))))
     (catch Exception e
       (log/error e "Exam session queue reader failed"))))

