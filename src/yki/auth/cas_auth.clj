(ns yki.auth.cas-auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [yki.boundary.cas-ticket-db :as cas-ticket-db]
            [clojure.tools.logging :refer [info error]]
            [yki.boundary.permissions :as permissions]
            [ring.util.http-response :refer [ok found]]
            [clojure.string :as str])
  (:import [java.util UUID]
           [fi.vm.sade.utils.cas CasLogout]))

(def unauthorized {:status 401
                   :body "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn- yki-permission? [permission]
  (= (permission "palvelu") "YKI")) ;use EPERUSTEET_YLOPS for testing

(defn- yki-permissions [org]
  {:oid (org "organisaatioOid")
   :permissions (filter yki-permission? (org "kayttooikeudet"))})

(defn- get-organizations-with-yki-permissions [organizations]
  (filter
   #(not-empty (:permissions %))
   (map yki-permissions organizations)))

(defn login [ticket request cas-client permissions-client url-helper db]
  (try
    (if ticket
      (let [username      (cas/validate-ticket (cas-client "/") ticket)
            _             (cas-ticket-db/create-ticket! db ticket)
            permissions   (permissions/virkailija-by-username permissions-client username)
            person-oid    (permissions "oidHenkilo")
            organizations (get-organizations-with-yki-permissions (permissions "organisaatiot"))
            session       (:session request)
            redirect-uri  (or (:success-redirect session) (url-helper :yki.default.cas.login-success.redirect))]
        (info "User" username "logged in")
        (if (empty? organizations)
          unauthorized
          (assoc
           (found redirect-uri)
           :session
           {:identity
            {:username username,
             :oid person-oid,
             :organizations organizations,
             :ticket ticket},
            :yki-session-id (str (UUID/randomUUID))})))
      unauthorized)
    (catch Exception e
      (error e "Cas ticket handling failed")
      (throw e))))

; (defn cas-logout
;   [logout-request session]
;   (info "cas-initiated logout")
;   (let [ticket (CasLogout/parseTicketFromLogoutRequest logout-request)]
;     (if (.isEmpty ticket)
;       (error "Could not parse ticket from CAS request")
;       (cas-store/logout (.get ticket)))
;     (ok)))

(defn logout
  [session url-helper]
  (info "user" (-> session :identity :username) "logged out")
  (assoc (found (url-helper :cas.logout)) :session {:identity nil}))
