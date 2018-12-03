(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :as log]
            [yki.boundary.email :as email]
            [yki.boundary.registration-db :as registration-db]
            [yki.boundary.yki-register :as yki-register]
            [yki.job.job-queue]
            [clj-time.local :as l]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defn take-with-error-handling
  "Takes message from queue and executes handler function with message.
  Rethrows exceptions if retry until limit is not reached so that message is not
  removed from queue and can be processed again."
  [queue retry-duration-in-days handler-fn]
  (try
    (pgq/take-with
     [request queue]
     (when request
       (try
         (handler-fn request)
         (catch Exception e
           (let [created (c/from-long (:created request))
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
  [_ {:keys [email-q url-helper retry-duration-in-days]}]
  {:pre [(some? url-helper) (some? email-q) (some? retry-duration-in-days)]}
  #(take-with-error-handling email-q retry-duration-in-days
                             (fn [email-req]
                               (log/info "Email queue reader sending email to:" (:recipients email-req))
                               (email/send-email url-helper email-req))))

(defmethod ig/init-key :yki.job.scheduled-tasks/exam-session-queue-reader
  [_ {:keys [exam-session-q url-helper db retry-duration-in-days disabled]}]
  {:pre [(some? url-helper) (some? exam-session-q) (some? db) (some? retry-duration-in-days)]}
  #(take-with-error-handling exam-session-q retry-duration-in-days
                             (fn [exam-session-req]
                               (log/info "Received request to sync exam session" exam-session-req)
                               (yki-register/sync-exam-session-and-organizer db url-helper disabled (:exam-session-id exam-session-req)))))

