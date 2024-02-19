(ns yki.middleware.error-boundary
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [ring.util.http-response :refer [bad-request internal-server-error]]
    [integrant.core :as ig]))

(defn- wrap-thrown-errors [handler]
  (fn [request]
    (let [trace-id (random-uuid)]
      (try
        (-> request
            (assoc ::trace-id trace-id)
            (handler))
        (catch Exception e
          (let [error-msg     (or (.getMessage e) "")
                error-type    (cond
                                (str/starts-with? error-msg "Request validation failed:")
                                :invalid-request
                                (str/starts-with? error-msg "Response validation failed:")
                                :invalid-response
                                :else
                                :unknown)
                error-data    {:error      "Error processing request"
                               :trace-id   trace-id
                               :error-type error-type}
                server-error? (= :invalid-response error-type)]
            (log/error e "Caught unhandled exception at error boundary! Returning sanitized error response instead. Trace-id: " trace-id)
            (if server-error?
              (internal-server-error error-data)
              (bad-request error-data))))))))

(defmethod ig/init-key ::with-error-handling [_ _]
  wrap-thrown-errors)
