(ns yki.middleware.payment
  (:require
    [ring.util.http-response :refer [unauthorized]]
    [yki.util.paytrail-payments :refer [sign-request]])
  (:import (java.io ByteArrayOutputStream InputStream)))

(defn- request->body [{:keys [body]}]
  (if (instance? InputStream body)
    (with-open [baos (ByteArrayOutputStream.)]
      (.transferTo ^InputStream body baos)
      (.toString baos))
    body))

(defn- valid-request?
  "Checks whether the request signature matches the request body and authentication headers.
   Handles authentication headers passed as both actual HTTP headers or as part of query parameters.

   If signature is valid, returns a map containing the (now exhausted) request body as a string.
   Returns nil if signature is invalid."
  [payment-config request]
  (let [{headers      :headers
         query-params :query-params} request
        authentication-headers (merge headers query-params)
        body                   (request->body request)]
    (when
      (= (sign-request payment-config authentication-headers body)
         (authentication-headers "signature"))
      {:authentication-headers authentication-headers
       :body                   body})))

(defn with-request-validation [payment-config handler]
  (fn
    ([request]
     (if-let [{body :body} (valid-request? payment-config request)]
       (handler (assoc request :body body))
       (unauthorized {:reason "Signature is not valid for request!"})))))
