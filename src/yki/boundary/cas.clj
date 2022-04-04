(ns yki.boundary.cas
  (:require
    [clj-cas.cas :as cas]
    [clojure.data.json :as json]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [yki.util.http-util :as http-util]))

(defprotocol CasAccess
  (validate-ticket [this ticket])
  (validate-oppija-ticket [this ticket callback-url])
  (get-cas-session [this])
  (cas-authenticated-post [this url body])
  (cas-authenticated-get [this url]))

(defn- request-with-json-body [request body]
  (-> request
      (assoc-in [:headers "Content-Type"] "application/json")
      (assoc :body (json/write-str body))))

(defn- create-params [cas-session-id body]
  (cond-> {:headers          {"Cookie" (str "JSESSIONID=" cas-session-id)}
           :caller-id        {"Caller-Id" "1.2.246.562.10.00000000001.yki"}
           :follow-redirects false}
          (some? body)
          (request-with-json-body body)))

(defn- cas-http [^fi.vm.sade.utils.cas.CasClient cas-client cas-params method url & [body]]
  (let [cas-session    (.fetchCasSession cas-client cas-params "JSESSIONID")
        cas-session-id (.run cas-session)
        resp           (http-util/do-request (merge {:url url :method method}
                                                    (create-params cas-session-id body)))]
    (if (= 302 (:status resp))
      (let [new-cas-session-id (.run (.fetchCasSession cas-client cas-params "JSESSIONID"))
            new-resp           (http-util/do-request (merge {:url url :method method}
                                                            (create-params new-cas-session-id body)))]
        new-resp)
      resp)))

(defn- cas-oppija-ticket-validation [ticket validate-service-url callback-url]
  (let [{:keys [status body]} @(http/get validate-service-url {:query-params {:ticket  ticket
                                                                              :service callback-url}
                                                               :headers      {"Caller-Id" "1.2.246.562.10.00000000001.yki"}})]
    (when (= status 200)
      body)))

(defrecord CasClient [^fi.vm.sade.utils.cas.CasClient cas-client url-helper cas-params]
  CasAccess
  (validate-ticket [_ ticket]
    (-> cas-client
        (.validateServiceTicketWithVirkailijaUsername (url-helper :yki.cas.login-success) ticket)
        (.run)))

  (validate-oppija-ticket [_ ticket callback-url]
    (let [validate-service-url  (url-helper :cas-oppija.validate-service)
          cas-validate-response (cas-oppija-ticket-validation ticket validate-service-url callback-url)]
      cas-validate-response))

  (cas-authenticated-get [_ url]
    (cas-http cas-client cas-params :get url))

  (cas-authenticated-post [_ url body]
    (cas-http cas-client cas-params :post url body))

  (get-cas-session [_]
    (-> cas-client
        (.fetchCasSession cas-params "JSESSIONID")
        (.run))))

(defmethod ig/init-key :yki.boundary.cas/cas-client [_ {:keys [url-helper cas-creds]}]
  (let [cas-client (cas/cas-client (url-helper :cas-client) "1.2.246.562.10.00000000001.yki")]
    (fn [service]
      (->CasClient cas-client url-helper (cas/cas-params service (:username cas-creds) (:password cas-creds))))))

