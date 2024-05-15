(ns profiling
  (:require [clj-async-profiler.core :as prof]))

(comment
  ; Profile a single expression.
  (prof/profile (dotimes [i 10000] (reduce + 0 (range i))))
  ; Or run profiler for a given time (in seconds)
  (prof/profile-for 10)
  ; Or start profiling session manually, do some computation, and then end the session manually.
  (prof/start)
  (do '(...))
  (prof/stop)
  ; View the results from your filesystem (by default, under /tmp/clj-async-profiler/results/)
  ; or start a local web UI:
  (prof/serve-ui 8008)

  )
