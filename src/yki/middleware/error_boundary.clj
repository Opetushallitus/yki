(ns yki.middleware.error-boundary
  (:require [clojure.tools.logging :as log]
            [ring.util.response :refer [bad-request]]))

(defn with-error-boundary [handler]
  (fn error-boundary [request]
    (try
      (handler request)
      (catch Exception e
        (log/error e "Caught exception at error boundary!")
        (bad-request "Bad request")))))
