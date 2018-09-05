(ns yki.handler.auth
  (:require [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [ring.util.response :as resp]
            [yki.handler.routing :as routing]
            ; [yki.middleware.auth-middleware :as auth]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response status]]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(defmethod ig/init-key :yki.handler/auth [_ {:keys [db auth]}]
   (context routing/virkailija-auth-root []
     :middleware [auth]
     (GET "/callback" [ticket]
         (response {:success ticket}))))
