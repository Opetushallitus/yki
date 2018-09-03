(ns yki.middleware.auth
  (:require [buddy.auth :refer [authenticated?]]
            [ring.util.http-response :refer [unauthorized]]))

(defn authenticated
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (unauthorized {:error "Not authorized"}))))
