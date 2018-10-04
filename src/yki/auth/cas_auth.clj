(ns yki.auth.cas-auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.permissions :as permissions]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str])
  (:import [java.util UUID]))

(def unauthorized {:status 401
                   :body "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- yki-permission? [permission]
  (= (permission "palvelu") "YKI")) ;EPERUSTEET_YLOPS

(defn- yki-permissions [org]
  {:oid (org "organisaatioOid")
   :permissions (filter yki-permission? (org "kayttooikeudet"))})

(defn- get-organizations-with-yki-permissions [organizations]
  (->> (map yki-permissions organizations)
       (filter #(not-empty (:permissions %)))))

(defn login [ticket request cas-client permissions-client url-helper]
  (try
    (if ticket
      (let [username (cas/validate-ticket (cas-client "/") ticket)
            permissions (permissions/virkailija-by-username permissions-client username)
            organizations (get-organizations-with-yki-permissions (permissions "organisaatiot"))
            session (:session request)]
        (info "user" username "logged in")
        (if (empty? organizations)
          unauthorized
          (-> (redirect (url-helper :yki.cas.login-success.redirect))
              (assoc :session {:identity  {:username username
                                           :organizations organizations
                                           :ticket ticket}
                               :yki-session-id (str (UUID/randomUUID))}))))
      unauthorized)
    (catch Exception e
      (error e "Cas ticket handling failed")
      (throw e))))

(defn logout [session url-helper]
  (info "user" (-> session :identity :username) "logged out")
  (-> (redirect (url-helper :cas.logout))
      (assoc :session {:identity nil})))
