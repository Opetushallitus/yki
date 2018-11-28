(ns yki.boundary.organization
  (:require
   [yki.util.http-util :as http-util]
   [clojure.tools.logging :refer [error info]]
   [jsonista.core :as json]))

(defn get-organization-by-oid
  [url-helper oid]
  (let [url                (url-helper :organisaatio-service.get-by-oid oid)
        response           (http-util/do-get url {})]
    (when (not= 200 (:status response))
      (throw (Exception. (str "Could get organzisation " oid))))))
