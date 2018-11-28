(ns yki.handler.registration
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.registration.registration :as registration]
            [yki.util.audit-log :as audit]
            [ring.util.http-response :refer [ok conflict not-found internal-server-error]]
            [yki.spec :as ys]
            [clj-time.core :as t]
            [ring.util.http-response :refer [ok]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log payment-config url-helper email-q onr-client]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? payment-config) (some? url-helper) (some? email-q) (some? onr-client)]}
  (api
   (context routing/registration-api-root []
     :coercion :spec
     :middleware [auth access-log]
     (POST "/" request
       :body [registration-init ::ys/registration-init]
       :return ::ys/id-response
       (audit/log-participant {:request request
                               :target-kv {:k audit/registration-init
                                           :v (:exam_session_id registration-init)}
                               :change {:type audit/create-op
                                        :new registration-init}})
       (registration/init-registration db (:session request) registration-init))
     (context "/:id" []
       (PUT "/" request
         :body [registration ::ys/registration]
         :path-params [id :- ::ys/id]
         :query-params [lang :- ::ys/language-code]
         :return ::ys/response
         (let [{:keys [oid error]} (registration/submit-registration db
                                                                     url-helper
                                                                     email-q
                                                                     lang
                                                                     (:session request)
                                                                     id
                                                                     registration
                                                                     (bigdec (payment-config :amount))
                                                                     onr-client)]
           (if oid
             (do
               (audit/log-participant {:request request
                                       :oid oid
                                       :target-kv {:k audit/registration
                                                   :v id}
                                       :change {:type audit/create-op
                                                :new registration}})
               (ok {:success true}))
             (do
               (error "Registration id:" id "failed with error" error)
               (internal-server-error {:success false
                                       :error error})))))))))
