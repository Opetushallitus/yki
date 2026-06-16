(ns yki.handler.registration
  (:require
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context DELETE GET POST]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok bad-request internal-server-error not-found]]
    [yki.boundary.registration-db :as registration-db]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit]))

(defn- sanitize-external-user-id [external-user-id]
  (if (and (string? external-user-id)
           (re-matches ys/ssn-regexp external-user-id))
    (str (subs external-user-id 0 7) "****")
    external-user-id))

(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log payment-helper url-helper email-q onr-client]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? url-helper) (some? email-q) (some? onr-client)]}
  (api
    (context routing/registration-api-root []
      :coercion :spec
      :middleware [auth access-log with-error-boundary]
      (POST "/init" request
        :body [registration-init ::ys/registration-init]
        (audit/log-participant {:request   request
                                :target-kv {:k audit/registration-init
                                            :v (:exam_session_id registration-init)}
                                :change    {:type audit/create-op
                                            :new  registration-init}})
        (registration/init-registration db
                                        (:session request)
                                        registration-init
                                        (:payment-config payment-helper)))
      (POST "/identify" request
        :body [registration-identify ::ys/registration-init]
        (audit/log-participant {:request   request
                                :target-kv {:k audit/registration-identify
                                            :v (:exam_session_id registration-identify)}
                                :change    {:type audit/create-op
                                            :new  registration-identify}})
        (registration/identify-registration db
                                            (:session request)
                                            registration-identify
                                            (:payment-config payment-helper)))
      (context "/:id" []
        (GET "/" _
          :path-params [id :- ::ys/id]
          (if-let [result (registration-db/get-registration-details-by-id db id)]
            (ok result)
            (not-found {:error "Registration not found"})))
        (POST "/submit" request
          :body [registration ::ys/registration]
          :path-params [id :- ::ys/id]
          :query-params [lang :- ::ys/language-code]
          :return ::ys/submit-registration-response
          (let [result (registration/submit-registration db url-helper payment-helper
                                                         email-q lang (:session request)
                                                         id registration onr-client)]
            (if-let [oid (:oid result)]
              (do
                (audit/log-participant {:request   request
                                        :oid       oid
                                        :target-kv {:k audit/registration
                                                    :v id}
                                        :change    {:type audit/create-op
                                                    :new  registration}})
                (ok (assoc result :success true)))
              (do
                (log/error "Registration id:" id "failed with error" (:error result))
                (internal-server-error {:success false
                                        :error   (:error result)})))))
        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [session        (:session request)
                {:keys [auth-method identity]} session
                participant-id (registration/get-participant-id db identity)]
            (if participant-id
              (if (registration-db/cancel-started-registration-for-participant! db session participant-id id)
                (do
                  (audit/log-participant {:request   request
                                          :target-kv {:k audit/registration
                                                      :v id}
                                          :change    {:type audit/cancel-op}})
                  (ok {:success true}))
                (do (log/warn "Cancelling registration for participant failed."
                              {:id               id
                               :participant-id   participant-id
                               :auth-method      auth-method
                               :external-user-id (-> identity
                                                     :external-user-id
                                                     (sanitize-external-user-id))})
                    (bad-request {:success false})))
              (do (log/warn "Cancelling registration failed. No participant found for session identity."
                            {:id               id
                             :auth-method      auth-method
                             :external-user-id (-> identity
                                                   :external-user-id
                                                   (sanitize-external-user-id))})
                  (bad-request {:success false})))))))))
