(ns yki.handler.fallback
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [ANY api context]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [not-found]]
    [yki.handler.routing :as routing]))

(defmethod ig/init-key :yki.handler/fallback [_ _]
  (api
    (context routing/api-root []
      (ANY "*" request
        (log/warn "Fallback handler invoked for"
                  (-> request
                      :request-method
                      name
                      str/upper-case)
                  (:uri request))
        (not-found "Invalid URL")))))
