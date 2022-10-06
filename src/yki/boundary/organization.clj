(ns yki.boundary.organization
  (:require
    [jsonista.core :as json]
    [yki.util.http-util :as http-util]))

(defn get-organization-by-oid
  [url-helper oid]
  (let [url      (url-helper :organisaatio-service.get-by-oid oid)
        response (http-util/do-get url {})]
    (if (= 200 (:status response))
      (json/read-value (:body response))
      (throw (RuntimeException. (str "Could not get organization " oid))))))

(defn get-organizations-by-oids [url-helper oids]
  (let [url      (url-helper :organisaatio-service.find-by-oids)
        response (http-util/do-post url {:body    (json/write-value-as-string oids)
                                         :headers {"content-type" "application/json"}})]
    (if (= 200 (:status response))
      (json/read-value (:body response))
      (throw (ex-info "Error when getting organizations by oids!" {:response response})))))
