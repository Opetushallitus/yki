(ns yki.auth.cas-auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [yki.boundary.onr :as onr]
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
  (= (:palvelu permission) "YKI")) ;use EPERUSTEET_YLOPS for testing

(defn- yki-permissions [org]
  {:oid (:organisaatioOid org)
   :permissions (filter yki-permission? (:kayttooikeudet org))})

(defn- get-organizations-with-yki-permissions [organizations]
  (filter
   #(not-empty (:permissions %))
   (map yki-permissions organizations)))

(defn login [ticket request cas-client permissions-client onr-client url-helper db]
  (try
    (if ticket
      (let [username      (cas/validate-ticket (cas-client "/") ticket)
            _             (cas-ticket-db/create-ticket! db ticket)
            permissions   (permissions/virkailija-by-username permissions-client username)
            person-oid    (:oidHenkilo permissions)
            person        (onr/get-person-by-oid onr-client person-oid)
            lang          (or (some #{(get-in person ["asiointiKieli" "kieliKoodi"])}
                                    ["fi" "sv"])
                              "fi")
            organizations (get-organizations-with-yki-permissions (:organisaatiot permissions))
            session       (:session request)
            redirect-uri  (if (:success-redirect session)
                            (str (:success-redirect session) "?lang=" lang)
                            (url-helper :yki.default.cas.login-success.redirect lang))]
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
             :lang lang,
             :ticket ticket},
            :auth-method "CAS"
            :yki-session-id (str (UUID/randomUUID))})))
      unauthorized)
    (catch Exception e
      (error e "Cas ticket handling failed")
      (throw e))))

(defn cas-logout
  [db logout-request]
  (info "cas-initiated logout")
  (let [ticket (CasLogout/parseTicketFromLogoutRequest logout-request)]
    (if (.isEmpty ticket)
      (error "Could not parse ticket from CAS request")
      (cas-ticket-db/delete-ticket! db (.get ticket)))
    (ok)))

(defn logout
  [session url-helper]
  (info "user" (-> session :identity :username) "logged out")
  (assoc (found (url-helper :cas.logout)) :session {:identity nil}))
