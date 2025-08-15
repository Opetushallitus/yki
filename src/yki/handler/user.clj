(ns yki.handler.user
  (:require
    [clojure.spec.alpha :as s]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok unauthorized]]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.registration.registration :as registration]
    [yki.spec :as ys]))

(defn- valid-session? [session]
  (let [auth-method (:auth-method session)]
    (or (= "SUOMIFI" auth-method)
        (and (= "EMAIL" auth-method)
             (not (= "PAYMENT" (:auth-target session)))))))

(defmethod ig/init-key :yki.handler/user [_ {:keys [db auth access-log environment]}]
  {:pre [(some? db) (some? auth) (some? access-log) (s/valid? ::ys/environment environment)]}
  (api
    (context routing/user-api-root []
      :coercion (when-not (#{:qa :prod} environment) :spec)
      :middleware [auth access-log with-error-boundary]
      (GET "/identity" {session :session}
        :return ::ys/user-identity-response
        (if (valid-session? session)
          (ok (update-in session [:identity] dissoc :ticket))
          (unauthorized)))
      (GET "/open-registrations" {session :session}
        :return ::ys/user-open-registrations-response
        (if (valid-session? session)
          (ok (registration/get-open-registrations-by-participant db session))
          (unauthorized))))))
