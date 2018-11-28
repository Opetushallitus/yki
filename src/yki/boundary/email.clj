(ns yki.boundary.email
  (:require [yki.util.http-util :as http-util]
            [clojure.string :as string]
            [jsonista.core :as json]))

(defn send-email
  [url-helper {:keys [recipients subject body]}]
  (let [url                (url-helper :ryhmasahkoposti-service)
        wrapped-recipients (mapv (fn [rcp] {:email rcp}) recipients)
        response           (http-util/do-post url {:headers      {"content-type" "application/json"}
                                                   :query-params {:sanitize "false"}
                                                   :body         (json/write-value-as-string {:email     {:subject subject
                                                                                                          :html    true
                                                                                                          :body    body
                                                                                                          :charset "UTF-8"}
                                                                                              :recipient wrapped-recipients})})]
    (when (not= 200 (:status response))
      (throw (Exception. (str "Could not send email to " (string/join recipients)))))))
