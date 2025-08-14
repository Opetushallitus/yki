(ns yki.boundary.cas
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [org.httpkit.client :as http])
  (:import
    (fi.vm.sade.javautils.nio.cas.impl CasSessionFetcher)
    (fi.vm.sade.javautils.nio.cas CasClient CasConfig CasClientBuilder)
    (io.netty.handler.codec.http HttpHeaders)
    (org.asynchttpclient RequestBuilder)
    (org.asynchttpclient Request Response)
    (org.asynchttpclient.request.body.multipart ByteArrayPart Part)))

(defprotocol CasAccess
  (validate-ticket [this ticket])
  (cas-authenticated-post [this url body])
  (cas-authenticated-post-multipart-form-data [this url body-parts])
  (cas-authenticated-get [this url]))

(def csrf-token "csrf")
(def caller-id "1.2.246.562.10.00000000001.yki")

(defn- process-response [^Response response]
  {:status  (.getStatusCode response)
   :body    (.getResponseBody response)
   :headers (.getHeaders response)})

(defn cas-oppija-ticket-validation [url-helper ticket callback-url]
  (let [validate-service-url (url-helper :cas-oppija.validate-service)
        {:keys [status body]} @(http/get validate-service-url {:query-params {:ticket  ticket
                                                                              :service callback-url}
                                                               :headers      {"Caller-Id" caller-id}})]
    (when (= 200 status)
      body)))

(defn- json-request [^String method url data]
  (let [body            (when data (json/write-str data))
        request-builder (RequestBuilder. method)]
    (doto request-builder
      (.addHeader "Content-Type" "application/json")
      (.setBody body)
      (.setUrl url))
    (.build request-builder)))

(defn- multipart-form-data-request [^String method url body-parts]
  (let [request-builder (RequestBuilder. method)]
    (doto request-builder
      (.addHeader "Content-Type" "multipart/form-data")
      (.setUrl url))
    (doseq [part body-parts]
      (.addBodyPart request-builder ^Part part))
    (.build request-builder)))

(defn- clear-ticket-stores! [^CasClient cas-client]
  (let [cls                   (.getClass cas-client)
        session-fetcher-field (.getDeclaredField cls "casSessionFetcher")
        _                     (.setAccessible session-fetcher-field true)
        session-fetcher       (.get session-fetcher-field cas-client)]
    (.clearTgtStore ^CasSessionFetcher session-fetcher)
    (.clearSessionStore ^CasSessionFetcher session-fetcher)))

(defn- refresh-tickets-response? [url-helper {:keys [headers status]}]
  (and (= 302 status)
       (when-let [location (.get ^HttpHeaders headers "Location")]
         (str/starts-with? location (url-helper :cas.login.root)))))

(defn- execute-with-retry! [^CasClient cas-client url-helper ^Request request]
  (let [execute! #(->> request
                       (.executeBlocking cas-client)
                       (process-response))
        response (execute!)]
    (if (refresh-tickets-response? url-helper response)
      (do
        (log/info "Got redirected to CAS-login; refreshing tickets.")
        (clear-ticket-stores! cas-client)
        (execute!))
      response)))

(defrecord YkiCasClient [^CasClient cas-client url-helper]
  CasAccess
  (validate-ticket [_ ticket]
    (let [validation-response (-> cas-client
                                  (.validateServiceTicketWithVirkailijaUsername (url-helper :yki.cas.login-success) ticket)
                                  (.get))]
      validation-response))
  (cas-authenticated-get [_ url]
    (try
      (execute-with-retry! cas-client url-helper (json-request "GET" url nil))
      (catch Exception e
        (log/error e "cas-authenticated-get failed!"))))
  (cas-authenticated-post [_ url body]
    (try
      (execute-with-retry! cas-client url-helper (json-request "POST" url body))
      (catch Exception e
        (log/error e "cas-authenticated-post failed!"))))
  (cas-authenticated-post-multipart-form-data [_ url body-parts]
    (try
      (execute-with-retry! cas-client url-helper (multipart-form-data-request "POST" url body-parts))
      (catch Exception e
        (log/error e "cas-authenticated-post-multipart-form-data failed!")))))

(defn create-cas-client [{:keys [username password]} url-helper service-url]
  (let [cas-config (CasConfig/SpringSessionCasConfig username password (url-helper :cas-client) service-url csrf-token caller-id)
        cas-client (CasClientBuilder/build cas-config)]
    (->YkiCasClient
      cas-client
      url-helper)))

(defmethod ig/init-key :yki.boundary.cas/cas-client [_ {:keys [url-helper cas-creds]}]
  (fn [service-url]
    (create-cas-client cas-creds url-helper service-url)))
