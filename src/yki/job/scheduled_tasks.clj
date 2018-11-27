(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.email :as email]
            [yki.boundary.registration-db :as registration-db]
            [yki.job.job-queue]
            [clj-time.local :as l]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defn handle-registration-state-changes [])

(defmethod ig/init-key :yki.job.scheduled-tasks/registration-state-handler
  [_ {:keys [db]}]
  #(if (= (job-db/try-to-acquire-lock! db worker-id "REGISTRATION_STATE_HANDLER" "599 SECONDS") 1)
     (do
       (try
         (info "Check started registrations expiry")
         (let [updated (registration-db/update-started-registrations-to-expired! db)]
           (info "Started registrations set to expired" updated))
         (catch Exception e
           (error e "Check started registrations expiry")))
       (try
         (info "Check submitted registrations expiry")
         (let [updated (registration-db/update-submitted-registrations-to-expired! db)]
           (info "Submitted registrations set to expired" updated))
         (catch Exception e
           (error e "Check submitted registrations expiry"))))))

(defmethod ig/init-key :yki.job.scheduled-tasks/email-queue-reader [_ {:keys [email-q url-helper]}]
  #(try
     (pgq/take-with
      [email-request email-q]
      (when email-request
        (info "Sending email" email-request)
        (email/send-email url-helper email-request)))
     (catch Exception e
       (error e "Email queue reader failed"))))

