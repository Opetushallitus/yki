(ns yki.job.scheduled-tasks
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [pgqueue.core :as pgq]
            [clojure.tools.logging :refer [info error]]
            [yki.job.job-queue]
            [clj-time.local :as l]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defmethod ig/init-key :yki.job.scheduled-tasks/payment-state-handler [_ {:keys [db]}]
  #(if (= (job-db/try-to-acquire-lock! db worker-id "PAYMENT_STATE_CHECKER" "9 MINUTES") 1)
     (info "payment-state-handler " (l/local-now))))

(defmethod ig/init-key :yki.job.scheduled-tasks/email-queue-reader [_ {:keys [email-q]}]
  #(pgq/take-with [item email-q]
                  (info "email-queue-reader" (l/local-now))))
