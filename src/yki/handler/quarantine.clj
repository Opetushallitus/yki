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

(defmethod ig/init-key :yki.handler/quarantine [_ {:keys [db url-helper access-log]}]
  {:pre [(some? db) (some? url-helper)]}
  (api
    (context routing/quarantine-api-root []
      :middleware [access-log]
      :coercion :spec
      (GET "/" {session :session}
        :return ::ys/quarantine-response
        (response {:quarantines (quarantine-db/get-quarantine db)}))
      (POST "/" request
        :body [quarantine ::ys/quarantine-type]
        :return ::ys/response
        (response {:success (quarantine-db/create-quarantine! db quarantine)}))
      (DELETE "/:id" []
        :path-params [id :- ::ys/id]
        :return ::ys/response
        (response {:success (quarantine-db/delete-quarantine! db id)}))
      (GET "/matches" {session :session}
        :return ::ys/quarantine-matches-response
        (response {:quarantines (quarantine-db/get-quarantine-matches db)}))
      (context "/:id/registration/:reg-id" []
        (PUT "/set" request
          :body [quarantined ::ys/quarantined]
          :path-params [id :- ::ys/id reg-id :- ::ys/id]
          :return ::ys/response
          (response {:success (quarantine-db/set-registration-quarantine! db
                                                                          id
                                                                          reg-id
                                                                          (:is_quarantined quarantined))}))))))
