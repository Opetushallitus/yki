(ns yki.handler.quarantine
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok not-found bad-request]]
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
      (GET "/" _
        :return ::ys/quarantine-response
        (ok {:quarantines (quarantine-db/get-quarantines db)}))
      (GET "/reviews" _
        :return ::ys/quarantine-reviews-response
        (ok {:reviews (quarantine-db/get-reviews db)}))
      (POST "/" request
        :body [quarantine ::ys/quarantine-type]
        :return ::ys/response
        (when-let [created (quarantine-db/create-quarantine! db quarantine)]
          (audit-log/log {:request   request
                          :target-kv {:k audit-log/quarantine
                                      :v (:id created)}
                          :change    {:type audit-log/create-op
                                      :new  quarantine}})
          (ok {:success true})))
      (PUT "/:id" request
        :path-params [id :- ::ys/id]
        :body [quarantine ::ys/quarantine-type]
        (if-let [existing (quarantine-db/get-quarantine db id)]
          (if-let [updated (quarantine-db/update-quarantine! db id quarantine)]
            (do
              (audit-log/log {:request   request
                              :target-kv {:k audit-log/quarantine
                                          :v id}
                              :change    {:type audit-log/update-op
                                          :old  existing
                                          :new  updated}})
              (ok {:success true}))
            (do
              (log/error "Update failed for existing quarantine with id" id)
              (bad-request)))
          (do
            (log/error "Attempted to update non-existing quarantine with id" id)
            (not-found))))
      (DELETE "/:id" request
        :path-params [id :- ::ys/id]
        :return ::ys/response
        (if-let [success (quarantine-db/delete-quarantine! db id)]
          (do (audit-log/log {:request   request
                              :target-kv {:k audit-log/quarantine
                                          :v id}
                              :change    {:type audit-log/delete-op}})
              (ok {:success success}))
          (not-found)))
      (GET "/matches" _
        :return ::ys/quarantine-matches-response
        (ok {:quarantine_matches (quarantine-db/get-quarantine-matches db)}))
      (context "/:id/registration/:reg-id" []
        (PUT "/set" request
          :body [quarantined ::ys/quarantined]
          :path-params [id :- ::ys/id reg-id :- ::ys/id]
          :return ::ys/response
          (try
            (if-let [quarantine-review (quarantine-db/set-registration-quarantine!
                                         db
                                         id
                                         reg-id
                                         (:is_quarantined quarantined)
                                         (get-in request [:session :identity :oid]))]
              (do
                (audit-log/log {:request   request
                                :target-kv {:k audit-log/quarantine-review
                                            :v (:id quarantine-review)}
                                :change    {:type audit-log/create-op
                                            :new  quarantine-review}})
                (ok {:success true}))
              (bad-request))
            (catch Exception e
              (log/error e "Exception occurred trying to set quarantine decision for id" id "and registration-id" reg-id)
              (bad-request))))))))
