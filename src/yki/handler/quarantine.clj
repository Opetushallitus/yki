(ns yki.handler.quarantine
  (:require
    [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [ring.util.response :refer [response]]
    [yki.util.audit-log :as audit-log]
    [yki.boundary.quarantine-db :as quarantine-db]
    [yki.handler.routing :as routing]
    [yki.middleware.access-log]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/quarantine [_ {:keys [access-log auth db url-helper]}]
  {:pre [(some? access-log) (some? auth) (some? db) (some? url-helper)]}
  (api
    (context routing/quarantine-api-root []
      :middleware [auth access-log]
      :coercion :spec
      (GET "/" {session :session}
        :return ::ys/quarantine-response
        (response {:quarantines (quarantine-db/get-quarantine db)}))
      (POST "/" request
        :body [quarantine ::ys/quarantine-type]
        :return ::ys/response
        (when-let [created (quarantine-db/create-quarantine!
                             db
                             (merge {:ssn          nil
                                     :email        nil
                                     :phone_number nil}
                                    quarantine))]
          (audit-log/log {:request   request
                          :target-kv {:k audit-log/quarantine
                                      :v (:id created)}
                          :change    {:type audit-log/create-op
                                      :new  quarantine}})
          (response {:success true})))
      (DELETE "/:id" request
        :path-params [id :- ::ys/id]
        :return ::ys/response
        (when-let [success (quarantine-db/delete-quarantine! db id)]
          (audit-log/log {:request   request
                          :target-kv {:k audit-log/quarantine
                                      :v id}
                          :change {:type audit-log/delete-op}})
          (response {:success success})))
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
