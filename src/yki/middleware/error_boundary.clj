(ns yki.middleware.error-boundary
  (:require
    [clojure.tools.logging :as log]
    [ring.util.http-response :refer [bad-request]]
    [integrant.core :as ig]))

(defn- wrap-thrown-errors [handler]
  (fn [request]
    (let [trace-id (random-uuid)]
      (try
        (-> request
            (assoc ::trace-id trace-id)
            (handler))
        (catch Exception e
          (log/error e "Caught unhandled exception at error boundary! Returning sanitized error response instead. Trace-id: " trace-id)
          (bad-request {:error    "Error processing request"
                        :trace-id trace-id}))))))

(defmethod ig/init-key ::with-error-handling [_ _]
  wrap-thrown-errors)
