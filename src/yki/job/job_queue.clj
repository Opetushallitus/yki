(ns yki.job.job-queue (:require
                       [integrant.core :as ig]
                       [pgqueue.core :as pgq]))

(defmethod ig/init-key :yki.job.job-queue/email-q [_ {:keys [db-config]}]
  (pgq/queue :email-send db-config))

