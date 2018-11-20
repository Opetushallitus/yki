(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.email :as email]
            [yki.job.job-queue]
            [clj-time.local :as l]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defn handle-registration-state-changes [])

(defmethod ig/init-key :yki.job.scheduled-tasks/registration-state-handler [_ {:keys [db]}]
  #(if (= (job-db/try-to-acquire-lock! db worker-id "REGISTRATION_STATE_HANDLER" "9 MINUTES") 1)
     (info "payment-state-handler" (l/local-now))))

(defmethod ig/init-key :yki.job.scheduled-tasks/email-queue-reader [_ {:keys [email-q url-helper]}]
  #(pgq/take-with [email-request email-q]
                  (do
                    (if email-request
                      (do (info "Email queue size" (pgq/count email-q))
                          (email/send-email url-helper email-request))))))
