(ns yki.boundary.cas
  (:require
    [clojure.data.json :as json]
    [org.httpkit.client :as http]
    [integrant.core :as ig]
    [clojure.tools.logging :as log])
  (:import
    (fi.vm.sade.javautils.nio.cas CasClient CasConfig CasConfig$CasConfigBuilder CasClientBuilder CasClientHelper)
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
  {:status (.getStatusCode response)
   :body   (.getResponseBody response)})

(defn- cas-oppija-ticket-validation [url-helper ticket callback-url]
  (let [validate-service-url (url-helper :cas-oppija.validate-service)]
    @(http/get validate-service-url {:query-params {:ticket  ticket
                                                    :service callback-url}
                                     :headers      {"Caller-Id" caller-id}})))

(defn- json-request [data]
  (let [json-str        (json/write-str data)
        request-builder (RequestBuilder.)]
    (doto request-builder
      (.addHeader "Content-Type" "application/json")
      (.setBody json-str))
    (.build request-builder)))

(defrecord YkiCasClient [^CasClient cas-client url-helper]
  CasAccess
  (validate-ticket [_ ticket]
    (log/info "validate-ticket called for ticket" ticket "and service-url" (url-helper :yki.cas.login-success))
    (let [validation-response (-> cas-client
                                  (.validateServiceTicketWithVirkailijaUsername (url-helper :yki.cas.login-success) ticket)
                                  (.get))]
      (log/info "validate-ticket returned with:" validation-response)
      validation-response))
  (validate-oppija-ticket [_ ticket callback-url]
    (log/info "validate-oppija-ticket called for ticket" ticket "and callback-url" callback-url)
    (let [{:keys [status body]} (cas-oppija-ticket-validation url-helper ticket callback-url)]
      (log/info "validate-oppija-ticket returned with status" status "and body" body)
      (when (= status 200)
        body)))
  (cas-authenticated-get [_ url]
    (log/info "cas-authenticated-get called with url" url)
    (try
      (let [helper   (CasClientHelper. cas-client)
            response (-> (.doGetSync helper url)
                         (process-response))]
        (log/info "cas-authenticated-get returned with:" response)
        response)
      (catch Exception e
        (log/error e "cas-authenticated-get failed!"))))
  (cas-authenticated-post [_ url body]
    (log/info "cas-authenticated-post called with url" url "and body" body)
    (let [helper   (CasClientHelper. cas-client)
          response (->> (json-request body)
                        (.doPostSync helper url)
                        (process-response))]
      (log/info "cas-authenticated-post returned with:" response)
      response)))

(defn create-cas-client [{:keys [username password]} url-helper service-url]
  (let [cas-config (CasConfig/SpringSessionCasConfig username password (url-helper :cas-client) service-url csrf-token caller-id)
        cas-client (CasClientBuilder/build cas-config)]
    (->YkiCasClient
      cas-client
      url-helper)))

(defmethod ig/init-key :yki.boundary.cas/cas-client [_ {:keys [url-helper cas-creds]}]
  (fn [service-url]
    (create-cas-client cas-creds url-helper service-url)))
