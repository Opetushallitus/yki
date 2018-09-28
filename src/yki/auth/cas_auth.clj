(ns yki.auth.cas-auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [taoensso.timbre :as timbre :refer [info error]]
            [yki.boundary.permissions :as permissions]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str]))

(defn- yki-permission? [permission]
  (= (permission "palvelu") "YKI"))

(defn- get-yki-permissions [organizations]
  (filter (fn [org]
            (some yki-permission? (org "kayttooikeudet")))
          organizations))

(defn login [ticket request cas-client permissions-client url-helper]
  (try
    (if ticket
      (let [username (cas/validate-ticket (cas-client "/") ticket)
            permissions (permissions/virkailija-by-username permissions-client username)
            yki-permissions (get-yki-permissions (permissions "organisaatiot"))
            session (:session request)]
        (info "user" username "logged in")
        (-> (redirect (url-helper :yki.cas.login-success.redirect))
            (assoc :session {:identity  {:username username
                                         :permissions yki-permissions
                                         :ticket ticket}})))
      {:status 401 :body "Unauthorized" :headers {"Content-Type" "text/plain; charset=utf-8"}})
    (catch Exception e
      (error e "Cas ticket handling failed")
      (throw e))))

(defn logout [session url-helper]
  (info "username" (-> session :identity :username) "logged out")
  (-> (redirect (url-helper :cas.logout))
      (assoc :session {:identity nil})))
