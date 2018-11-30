(ns yki.job.job-queue (:require
                       [integrant.core :as ig]
                       [pgqueue.core :as pgq]))

(def queue-config (atom nil))

(defmethod ig/init-key :yki.job.job-queue/init [_ {:keys [db-config]}]
  (reset! queue-config db-config))

(defmethod ig/init-key :yki.job.job-queue/email-q [_ _]
  (pgq/queue :email-send-v1 @queue-config))

(defmethod ig/init-key :yki.job.job-queue/exam-session-q [_ _]
  (pgq/queue :exam-session-send-v1 @queue-config))



