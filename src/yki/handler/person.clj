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
          (POST "/relocate" {session :session}
            :path-params [registration-id :- ::ys/registration_id]
            :body [relocate-request ::ys/relocate-request]
            :return ::ys/response
            (let [
                  ; TODO Authorization (registration to relocate belongs to user)
                  ; TODO Ensure user can relocate only transferable registrations (is COMPLETED, transfer window (TBD!) is open, has not been already transfered)
                  ; TODO Ensure user can relocate only to suitable targets (has space, meets other conditions)
                  to-exam-session-id (:to_exam_session_id relocate-request)
                  organizer-oid      (exam-session-db/get-exam-session-organizer-oid db registration-id)
                  success?           (exam-session-db/update-registration-exam-session! db to-exam-session-id registration-id organizer-oid)]
              (if success?
                (ok {:success true})
                (ok {:success false})))))))))
