(ns yki.boundary.cas-rewrite
  (:require
    [clojure.data.json :as json]
    [yki.boundary.cas-access :refer [CasAccess] :as cas]
    [integrant.core :as ig]
    [clojure.tools.logging :as log])
  (:import
    (fi.vm.sade.javautils.nio.cas CasClient CasConfig CasConfig$CasConfigBuilder CasClientBuilder CasClientHelper)
    (org.asynchttpclient.netty NettyResponse)))

(def csrf-token "csrf")
(def caller-id "1.2.246.562.10.00000000001.yki")

(defn process-response [^NettyResponse response]
  {:status (.getStatusCode response)
   :body   (.getResponseBody response)})

(defrecord NewCasClient [^CasClient cas-client url-helper]
  CasAccess
  (validate-ticket [_ ticket]
    (log/info "validate-ticket called for ticket" ticket "and service-url" (url-helper :yki.cas.login-success))
    (let [validation-response (-> cas-client
                                  (.validateServiceTicketWithVirkailijaUsername (url-helper :yki.cas.login-success) ticket)
                                  (.get))]
      (log/info "got validation-response:" validation-response)
      validation-response))
  (validate-oppija-ticket [_ ticket callback-url]
    (log/info "validate-oppija-ticket called for ticket" ticket "and callback-url" callback-url)
    (let [validation-response
          (-> cas-client
              (.validateServiceTicketWithOppijaAttributes callback-url ticket)
              (.get))]
      (log/info "got validation-response:" validation-response)))
  (cas-authenticated-get [_ url]
    (log/info "cas-authenticated-get called with url" url)
    (let [helper   (CasClientHelper. cas-client)
          response (-> (.doGetSync helper url)
                       (process-response))]
      (log/info "got response" response)
      response))
  (cas-authenticated-post [_ url body]
    (log/info "cas-authenticated-post called with url" url "and body" body)
    (let [helper   (CasClientHelper. cas-client)
          response (-> (.doPostSync helper url (json/write-str body))
                       (process-response))]
      (log/info "got response" response)
      response)))

(defn create-cas-client [{:keys [username password]} url-helper service-url]
  (let [cas-config (-> (CasConfig$CasConfigBuilder. username password (url-helper :cas-client) service-url csrf-token caller-id "")
                       (.setJsessionName "JSESSIONID")
                       (.build))
        cas-client (CasClientBuilder/build cas-config)]
    (->NewCasClient
      cas-client
      url-helper)))

(defmethod ig/init-key :yki.boundary.cas-rewrite/cas-client [_ {:keys [url-helper cas-creds]}]
  (fn [service-url]
    (create-cas-client cas-creds url-helper service-url)))
