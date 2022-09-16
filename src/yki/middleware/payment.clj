(ns yki.middleware.payment
  (:require
    [ring.util.http-response :refer [unauthorized]]
    [yki.util.payments-api :refer [valid-request?]]))

(defn with-request-validation [payment-config handler]
  (fn
    ([request]
     (if-let [{body :body} (valid-request? payment-config request)]
       (handler (assoc request :body body))
       (unauthorized {:reason "Signature is not valid for request!"})))))
