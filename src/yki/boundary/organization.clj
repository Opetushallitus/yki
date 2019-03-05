(ns yki.boundary.organization
  (:require
   [yki.util.http-util :as http-util]
   [clojure.tools.logging :refer [error info]]
   [jsonista.core :as json]))

(defn get-organization-by-oid
  [url-helper oid]
  (let [url                (url-helper :organisaatio-service.get-by-oid oid)
        response           (http-util/do-get url {})]
    (if (= 200 (:status response))
      (json/read-value (:body response))
      (throw (RuntimeException. (str "Could not get organization " oid))))))
