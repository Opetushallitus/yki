(ns yki.handler.registration
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.registration.registration :as registration]
            [yki.spec :as ys]
            [clj-time.core :as t]
            [ring.util.http-response :refer [ok]]
            [integrant.core :as ig]))

(defn- get-participant-from-session [session]
  (let [identity (:identity session)]
    {:external_user_id (or (:ssn identity) (:external-user-id identity))
     :email (:external-user-id identity)}))

(defn get-participant-id [db session]
  (:id (registration-db/get-or-create-participant! db (get-participant-from-session session))))

(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log payment-config url-helper email-q]}]
  (api
   (context routing/registration-api-root []
     :coercion :spec
     :middleware [auth access-log]
     (POST "/" request
       :body [registration-init ::ys/registration-init]
       :return ::ys/id-response
       (let [id (registration/init-registration db (:session request) registration-init)]
         (ok {:id id})))
     (context "/:id" []
       (PUT "/" request
         :body [registration ::ys/registration]
         :path-params [id :- ::ys/id]
         :query-params [lang :- ::ys/language-code]
         :return ::ys/response
         (registration/submit-registration db url-helper email-q lang (:session request) id registration (bigdec (payment-config :amount)))
         (ok {:success true}))))))
