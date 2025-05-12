(ns yki.handler.person
  (:require
    [compojure.api.sweet :refer [api context GET POST DELETE]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok not-found]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.person-db :as person-db]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.spec :as ys]
    [yki.registration.registration :as registration]))

(defmethod ig/init-key :yki.handler/person [_ {:keys [db auth access-log environment onr-client]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? onr-client)]}
  (api
    (context routing/person-api-root []
      :coercion (when-not (#{:qa :prod} environment) :spec)
      :middleware [auth access-log with-error-boundary]
      (GET "/" {session :session}
        :return ::ys/person
        (let [oid (get-in session [:identity :oid])]
          (if oid
            (ok (registration/get-person-and-registrations
                  db
                  oid))
            (not-found "no oid in session"))))
      (POST "/" {session :session}
        :body [person ::ys/person]
        :return ::ys/response
        (let [oid (get-in session [:identity :oid])]
          (if (person-db/upsert-person! db (assoc person :oid oid))
            (ok {:success true})
            (ok {:success false}))))
      (context routing/registration-uri []
        (context "/:registration-id" []
          (DELETE "/" {session :session}
            :path-params [registration-id :- ::ys/registration_id]
            :return ::ys/response
            (let [oid (get-in session [:identity :oid])]
              (if (exam-session-db/cancel-registration! db registration-id)
                (ok {:success true})
                (ok {:success false}))))
          (GET "/relocate" {session :session}
            :path-params [registration-id :- ::ys/registration_id]
            (let [; TODO What if user has no oid, ie. is authenticated with email link only?
                  oid     (get-in session [:identity :oid])
                  results (person-db/get-registration-relocate-details db oid registration-id)]
              (ok results)))
          (POST "/relocate" {session :session}
            :path-params [registration-id :- ::ys/registration_id]
            :body [relocate-request ::ys/relocate-request]
            :return ::ys/response
            (let [oid                (get-in session [:identity :oid])
                  to-exam-session-id (:to_exam_session_id relocate-request)
                  result             (person-db/relocate-registration! db oid registration-id to-exam-session-id)]
              (if result
                (ok {:success true})
                (ok {:success false})))))))))
