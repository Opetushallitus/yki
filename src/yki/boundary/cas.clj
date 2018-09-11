(ns yki.boundary.cas
  (:require
   [integrant.core :as ig]
   [yki.util.http-util :as http-util]
   [cheshire.core :as json]
   [clj-util.cas :as cas]))

(defprotocol CasAccess
  (validate-ticket [this ticket])
  (get-cas-session [this cas-params])
  (cas-authenticated-post [this url body])
  (cas-authenticated-get [this url]))

(defn- request-with-json-body [request body]
  (-> request
      (assoc-in [:headers "Content-Type"] "application/json")
      (assoc :body (json/generate-string body))))

(defn- create-params [cas-session-id body]
  (cond-> {:headers {"Cookie" (str "JSESSIONID=" cas-session-id)}
           :follow-redirects false}
    (some? body)
    (request-with-json-body body)))

(defn- cas-http [cas-client cas-params method url & [body]]
  (let [cas-session-id (.run (.fetchCasSession cas-client cas-params))
        resp (http-util/do-request (merge {:url url :method method}
                                          (create-params cas-session-id body)))]
    (if (= 302 (:status resp))
      (do
        (reset! cas-session-id (.run (.fetchCasSession cas-client cas-params)))
        (http-util/do-request (merge {:url url :method method}
                                     (create-params cas-session-id body))))
      resp)))

(defrecord CasClient [url-helper cas-params]
  CasAccess
  (validate-ticket [_ ticket]
    (let [cas-client (cas/cas-client (url-helper :cas-client))
          username   (.run (.validateServiceTicket cas-client (url-helper :yki.cas.login-success) ticket))]
      username))

  (cas-authenticated-get [_ url]
    (cas-http (cas/cas-client (url-helper :cas-client)) cas-params :get url))

  (cas-authenticated-post [_ url body]
    (cas-http (cas/cas-client (url-helper :cas-client)) cas-params :post url body))

  (get-cas-session [_ cas-params] (.run (.fetchCasSession (cas/cas-client (url-helper :cas-client) cas-params)))))

(defmethod ig/init-key :yki.boundary.cas/cas-client [_ {:keys [url-helper cas-creds]}]
  (fn [service]
    (->CasClient url-helper (cas/cas-params service (:username cas-creds) (:password cas-creds)))))
