(ns yki.job.payment-state-checker
  (:require [integrant.core :as ig]
            [clojure.string :as str]
            [clj-time.local :as l]
            [yki.boundary.job-db :as job-db])
  (:import [java.util UUID]))

(defonce worker-id (str (UUID/randomUUID)))

(defmethod ig/init-key :yki.job/payment-state-checker [_ {:keys [db]}]
  #(if (= (job-db/try-to-acquire-lock! db worker-id "PAYMENT_STATE_CHECKER" "1 MINUTE") 1)
     (println (l/local-now))))
