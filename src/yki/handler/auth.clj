(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [yki.handler.routing :as routing]
            [yki.boundary.cas :as cas]
            [yki.auth.cas-auth :as cas-auth]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response status redirect]]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [db auth url-helper cas-client]}]
  (api
   (context routing/virkailija-auth-root []
     :middleware [auth]
     (GET "/callback" [ticket :as request]
       (try
         (if ticket
           (let [username (.validate-ticket cas-client ticket)
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
     (GET "/logout" {session :session}
       (cas-auth/logout session url-helper))
     (GET "/user" {session :session}
       (response {:session (update-in session [:identity] dissoc :ticket)})))))
