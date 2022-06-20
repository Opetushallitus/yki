(ns yki.boundary.email
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [jsonista.core :as json]
    [yki.util.http-util :as http-util]))

(defn- log-disabled-email [recipients subject body]
  (log/info
    (str/join "\r\n"
              ["Email sending is disabled, logging instead:"
                  (str "Recipients: " recipients)
                  (str "Subject: " subject)
                  (str "Body: " body)]))
  {:status 200})

(defn send-email
  [url-helper {:keys [recipients subject body]} disabled]
  (let [url                (url-helper :ryhmasahkoposti-service)
        wrapped-recipients (mapv (fn [rcp] {:email rcp}) recipients)
        response           (if disabled
                             (log-disabled-email recipients subject body)
                             (http-util/do-post url {:headers      {"content-type" "application/json; charset=UTF-8"}
                                                     :query-params {:sanitize "false"}
                                                     :body         (json/write-value-as-string {:email     {:subject subject
                                                                                                            :html    true
                                                                                                            :body    body
                                                                                                            :charset "UTF-8"}
                                                                                                :recipient wrapped-recipients})}))]
    (when (not= 200 (:status response))
      (throw (Exception. (str "Could not send email to " (str/join recipients)))))))
