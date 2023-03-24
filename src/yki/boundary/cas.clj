(ns yki.boundary.cas
  (:require
    [clojure.data.json :as json]
    [org.httpkit.client :as http]
    [integrant.core :as ig]
    [clojure.tools.logging :as log])
  (:import
    (fi.vm.sade.javautils.nio.cas CasClient CasConfig CasClientBuilder CasClientHelper)
    (org.asynchttpclient Response)
    (org.asynchttpclient RequestBuilder)))

(defprotocol CasAccess
  (validate-ticket [this ticket])
  (validate-oppija-ticket [this ticket callback-url])
  (cas-authenticated-post [this url body])
  (cas-authenticated-get [this url]))


(def csrf-token "csrf")
(def caller-id "1.2.246.562.10.00000000001.yki")

(defn process-response [^Response response]
  {:status         (.getStatusCode response)
   :body           (.getResponseBody response)
   :headers        (.getHeaders response)
   :is-redirected? (.isRedirected response)})

(defn- cas-oppija-ticket-validation [url-helper ticket callback-url]
  (let [validate-service-url (url-helper :cas-oppija.validate-service)]
    @(http/get validate-service-url {:query-params {:ticket  ticket
                                                    :service callback-url}
                                     :headers      {"Caller-Id" caller-id}})))

(defn- json-request [^String method url data]
  (let [json-str        (json/write-str data)
        request-builder (RequestBuilder. method)]
    (doto request-builder
      (.addHeader "Content-Type" "application/json")
      (.setBody json-str)
      (.setUrl url))
    (.build request-builder)))

(defrecord YkiCasClient [^CasClient cas-client url-helper]
  CasAccess
  (validate-ticket [_ ticket]
    (let [validation-response (-> cas-client
                                  (.validateServiceTicketWithVirkailijaUsername (url-helper :yki.cas.login-success) ticket)
                                  (.get))]
      validation-response))
  (validate-oppija-ticket [_ ticket callback-url]
    (let [{:keys [status body]} (cas-oppija-ticket-validation url-helper ticket callback-url)]
      (when (= status 200)
        body)))
  (cas-authenticated-get [_ url]
    (try
      (let [helper (CasClientHelper. cas-client)]
        (-> (.doGetSync helper url)
            (process-response)))
      (catch Exception e
        (log/error e "cas-authenticated-get failed!"))))
  (cas-authenticated-post [_ url body]
    (try
      (->> (json-request "POST" url body)
           (.executeBlocking cas-client)
           (process-response))
      (catch Exception e
        (log/error e "cas-authenticated-post failed!")))))

(defn create-cas-client [{:keys [username password]} url-helper service-url]
  (let [cas-config (CasConfig/SpringSessionCasConfig username password (url-helper :cas-client) service-url csrf-token caller-id)
        cas-client (CasClientBuilder/build cas-config)]
    (->YkiCasClient
      cas-client
      url-helper)))

(defmethod ig/init-key :yki.boundary.cas/cas-client [_ {:keys [url-helper cas-creds]}]
  (fn [service-url]
    (create-cas-client cas-creds url-helper service-url)))
