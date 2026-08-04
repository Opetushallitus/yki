(ns yki.job.scheduler
  "A restartable variant of duct.scheduler/simple.

  Runs jobs (zero-argument functions) at regular intervals on a scheduled
  thread pool. Unlike duct.scheduler/simple, the executor is held in an atom
  so that it can be shut down and recreated at runtime without restarting the
  whole Integrant system. This is needed because a job that hangs or throws a
  Throwable is silently dropped by scheduleAtFixedRate and never runs again."
  (:require
    [clojure.tools.logging :as log]
    [integrant.core :as ig])
  (:import
    (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

(defn- start-executor [thread-pool-size jobs]
  (let [executor (ScheduledThreadPoolExecutor. thread-pool-size)]
    (doseq [{:keys [delay interval run]} jobs]
      (.scheduleAtFixedRate executor
                            ^Runnable run
                            (long (* 1000 (or delay interval)))
                            (long (* 1000 interval))
                            TimeUnit/MILLISECONDS))
    executor))

(defn- stop-executor! [^ScheduledThreadPoolExecutor executor]
  ; shutdownNow interrupts running jobs. Jobs blocked on IO that doesn't react
  ; to interrupts may keep running to completion on the orphaned thread; this is
  ; tolerable, as the scheduled tasks acquire a lock from the database before
  ; doing any work.
  (count (.shutdownNow executor)))

(defn status
  [{:keys [jobs executor]}]
  (let [^ScheduledThreadPoolExecutor executor @executor]
    ; Periodic tasks sit in the executor queue between runs, so a scheduled-count
    ; lower than job-count means some jobs have been dropped by the executor.
    {:job-count       (count jobs)
     :scheduled-count (.size (.getQueue executor))
     :running-count   (.getActiveCount executor)
     :completed-count (.getCompletedTaskCount executor)
     :pool-size       (.getPoolSize executor)
     :shutdown?       (.isShutdown executor)}))

(defn restart!
  "Shuts down the current executor and reschedules all jobs on a new one.
  Returns the status of the newly started executor."
  [{:keys [thread-pool-size jobs executor] :as scheduler}]
  (locking executor
    (let [dropped (stop-executor! @executor)]
      (log/warn "Restarting scheduler, dropped" dropped "scheduled jobs")
      (reset! executor (start-executor thread-pool-size jobs))))
  (status scheduler))

(defmethod ig/init-key :yki.job.scheduler/scheduler
  [_ {:keys [thread-pool-size jobs] :or {thread-pool-size 32}}]
  (log/info "Starting scheduler with" (count jobs) "jobs")
  {:thread-pool-size thread-pool-size
   :jobs             jobs
   :executor         (atom (start-executor thread-pool-size jobs))})

(defmethod ig/halt-key! :yki.job.scheduler/scheduler
  [_ {:keys [executor]}]
  (stop-executor! @executor))
