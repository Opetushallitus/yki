(ns yki.handler.quarantine
  (:require
    [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [ring.util.response :refer [response]]
    [yki.boundary.quarantine-db :as quarantine-db]
    [yki.handler.routing :as routing]
    [yki.middleware.access-log]
    [yki.spec :as ys]))

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
        (let [success (quarantine-db/create-quarantine!
                        db
                        (merge {:ssn          nil
                                :email        nil
                                :phone_number nil}
                               quarantine))]
          (response {:success success})))
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
