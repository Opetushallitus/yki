(ns yki.handler.user
  (:require
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok bad-request internal-server-error]]
    [yki.handler.routing :as routing]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit]))

(defmethod ig/init-key :yki.handler/user [_ {:keys [db auth access-log user-config]}]
  {:pre [(some? db) (some? auth) (some? access-log)]}
  (api
    (context routing/user-api-root []
      :coercion :spec
      :middleware [auth access-log]
      (GET "/identity" {session :session}
        :return ::ys/user-identity-response
        (ok (or user-config (update-in session [:identity] dissoc :ticket))))
      (GET "/open-registrations" request
        :return ::ys/user-open-registrations-response
        (ok (registration/get-open-registrations-by-participant db (or user-config (:session request))))))))
