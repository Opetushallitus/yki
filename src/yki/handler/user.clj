(ns yki.handler.user
  (:require
    [clojure.spec.alpha :as s]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok]]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/user [_ {:keys [db auth access-log environment onr-client]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? onr-client) (s/valid? ::ys/environment environment)]}
  (api
    (context routing/user-api-root []
      :coercion (when-not (#{:qa :prod} environment) :spec)
      :middleware [auth access-log with-error-boundary]
      (GET "/identity" {session :session}
        :return ::ys/user-identity-response
        (ok (update-in session [:identity] dissoc :ticket)))
      (GET "/open-registrations" request
        :return ::ys/user-open-registrations-response
        (ok (registration/get-open-registrations-by-participant db (:session request))))
      (GET "/person-registrations" {session :session}
        :query-params [lang :- ::ys/language-code]
        (ok (registration/get-person-and-registrations db lang (get-in session [:identity :oid]) onr-client))))))
