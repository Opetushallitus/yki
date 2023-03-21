(ns yki.handler.registration
  (:require [compojure.api.sweet :refer [api context POST]]
            [yki.handler.routing :as routing]
            [yki.registration.registration :as registration]
            [yki.util.audit-log :as audit]
            [ring.util.http-response :refer [ok internal-server-error]]
            [yki.spec :as ys]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log payment-helper url-helper email-q onr-client user-config]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? url-helper) (some? email-q) (some? onr-client)]}
  (api
   (context routing/registration-api-root []
     :coercion :spec
     :middleware [auth access-log]
     (POST "/init" request
       :body [registration-init ::ys/registration-init]
       :return ::ys/registration-init-response
       (audit/log-participant {:request request
                               :target-kv {:k audit/registration-init
                                           :v (:exam_session_id registration-init)}
                               :change {:type audit/create-op
                                        :new registration-init}})
       (registration/init-registration db
                                       (or user-config (:session request))
                                       registration-init
                                       (:payment-config payment-helper)))
     (context "/:id" []
       (POST "/submit" request
         :body [registration ::ys/registration]
         :path-params [id :- ::ys/id]
         :query-params [lang :- ::ys/language-code
                        {use-yki-ui :- ::ys/use-yki-ui nil}]
         :return ::ys/response
         (let [{:keys [oid error]} (registration/submit-registration db
                                                                     url-helper
                                                                     payment-helper
                                                                     email-q
                                                                     lang
                                                                     (or user-config (:session request))
                                                                     id
                                                                     registration
                                                                     onr-client
                                                                     use-yki-ui)]
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
               (log/error "Registration id:" id "failed with error" error)
               (internal-server-error {:success false
                                       :error
                                       error})))))))))
