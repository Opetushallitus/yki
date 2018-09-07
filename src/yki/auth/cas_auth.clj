(ns yki.auth.cas-auth
    (:require [compojure.api.sweet :refer :all]
        [integrant.core :as ig]
        [yki.handler.routing :as routing]
        [yki.boundary.cas-access :as cas]
        [clojure.tools.logging :refer [info error]]
        [ring.util.response :refer [response status redirect]]
        [clojure.string :as str]))

(defn logout [session url-helper]
    (info "username" (-> session :identity :username) "logged out")
    ; (cas-store/logout (-> session :identity :ticket))
    (-> (redirect (url-helper :cas.logout))
        (assoc :session {:identity nil})))
