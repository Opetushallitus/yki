(ns yki.handler.registration
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
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

(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log payment-config]}]
  (api
   (context routing/registration-api-root []
     :coercion :spec
     :middleware [auth access-log]
     (POST "/" request
       :body [registration-init ::ys/registration-init]
       :return ::ys/id-response
       (let [participant-id (get-participant-id db (:session request))
             registration (registration-db/create-registration! db (assoc registration-init
                                                                          :participant_id participant-id
                                                                          :started_at (t/now)))]
         (ok {:id (:id registration)})))
     (context "/:id" []
       (PUT "/" [lang :as request]
         :body [registration ::ys/registration]
         :path-params [id :- ::ys/id]
         :return ::ys/response
         (let [participant-id (get-participant-id db (:session request))
               payment {:registration_id id
                        :lang (or lang "fi")
                        :amount (bigdec (payment-config :amount))}
               update-registration {:id id
                                    :participant_id participant-id}]
           (registration-db/create-payment-and-update-registration! db payment update-registration)
           (ok {:success true})))))))
