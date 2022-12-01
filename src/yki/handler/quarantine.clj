(ns yki.handler.quarantine
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [pgqueue.core :as pgq]
    [ring.util.response :refer [response not-found]]
    [yki.boundary.quarantine-db :as quarantine-db]
    [yki.handler.routing :as routing]
    [yki.middleware.access-log]
    [yki.middleware.auth :as auth]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit-log]))

(defmethod ig/init-key :yki.handler/quarantine [_ {:keys [db]}]
  {:pre [(some? db)]}
  (api
    (context routing/quarantine-api-root []
      :coercion :spec
      (GET "/" {session :session}
        :return ::ys/quarantine-response
        (response {:quarantines (quarantine-db/get-quarantine db)})))
      (GET "/matches" {session :session}
        :return ::ys/quarantine-matches-response
        (response {:quarantines (quarantine-db/get-quarantine-matches db)}))
      (context "/:id/registration/:reg-id" [id reg-id]
        (PUT "/set" request
          :body [quarantined ::ys/quarantined]
          :return ::ys/response
          (let [ret (quarantine-db/update-registration-quarantine! db id reg-id quarantined)]
            (response {:success ret}))))))
