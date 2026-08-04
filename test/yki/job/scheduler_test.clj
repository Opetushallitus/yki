(ns yki.job.scheduler-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [integrant.core :as ig]
    [yki.job.scheduler :as scheduler])
  (:import (java.util.concurrent ExecutorService)))

(defn- wait-until [predicate]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (predicate) true
        (< deadline (System/currentTimeMillis)) false
        :else (do (Thread/sleep 50)
                  (recur))))))

(deftest scheduler-restart-test
  (let [runs      (atom 0)
        component (ig/init-key :yki.job.scheduler/scheduler
                               {:thread-pool-size 2
                                :jobs             [{:interval 1 :delay 0 :run #(swap! runs inc)}
                                                   {:interval 1 :delay 0 :run #(throw (AssertionError. "job failed"))}]})]
    (try
      (testing "a job throwing a Throwable is dropped by the executor"
        (is (wait-until #(= 1 (:scheduled-count (scheduler/status component)))))
        (is (= 2 (:job-count (scheduler/status component))))
        (is (pos? @runs)))
      (testing "restart shuts down the previous executor and reschedules all jobs"
        (let [previous    @(:executor component)
              runs-before @runs]
          (scheduler/restart! component)
          (is (not= previous @(:executor component)))
          (is (.isShutdown ^ExecutorService previous))
          (is (wait-until #(< runs-before @runs)))))
      (finally
        (ig/halt-key! :yki.job.scheduler/scheduler component)))
    (is (:shutdown? (scheduler/status component)))))
