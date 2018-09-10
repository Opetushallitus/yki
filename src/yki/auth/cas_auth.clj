(ns yki.auth.cas-auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.permissions :as permissions]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str]))

(defn login [ticket request cas-client permissions-client]
  (try
    (if ticket
      (let [username (cas/validate-ticket (cas-client "/") ticket)
            user (permissions/virkailija-by-username permissions-client username)
            session (:session request)]
        (do
          (info "user" username "logged in")
          (-> (redirect "/yki/auth/cas/user")
              (assoc :session {:identity  {:username username
                                           :ticket ticket}}))))
      {:status 401 :body "Unauthorized" :headers {"Content-Type" "text/plain; charset=utf-8"}})
    (catch Exception e
      (error e "Cas ticket handling failed")
      (throw e))))

(defn logout [session url-helper]
  (info "username" (-> session :identity :username) "logged out")
  (-> (redirect (url-helper :cas.logout))
      (assoc :session {:identity nil})))
