(ns yki.handler.person
  (:require
   [compojure.api.sweet :refer [api context GET POST DELETE]]
   [integrant.core :as ig]
   [ring.util.http-response :refer [ok not-acceptable not-found]]
   [yki.boundary.exam-session-db :as exam-session-db]
   [yki.boundary.person-db :as person-db]
   [yki.boundary.registration-db :as registration-db]
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
      (context "/:oid" []
        (GET "/" {session :session}
             :query-params [lang :- ::ys/lang]
             :path-params [oid :- ::ys/oid]
             :return ::ys/person
             (let [session-oid (get-in session [:identity :oid])]
               (if (= oid session-oid)
                 (ok (registration/get-person-and-registrations
                      db
                      lang
                      oid onr-client)))))
        (POST "/" {session :session}
          :body [person ::ys/person]
          :path-params [oid :- ::ys/oid]
          :return ::ys/response
          (let [session-oid (get-in session [:identity :oid])]
            (if (= oid session-oid)
              (if (person-db/upsert-person! db (assoc person :oid oid))
                (ok {:success true})
                (ok {:success false}))
              (ok {:success false}))))
        (context routing/registration-uri []
          (context "/:registration-id" []
            (DELETE "/" {session :session}
              :path-params [oid :- ::ys/oid registration-id :- ::ys/registration_id]
              :return ::ys/response
              (let [session-oid (get-in session [:identity :oid])]
                (if (= oid session-oid)
                  (if (exam-session-db/cancel-registration! db registration-id)
                    (ok {:success true})
                    (ok {:success false}))
                  (ok {:success false}))))
            (POST "/relocate" {session :session}
              :path-params [oid :- ::ys/oid registration-id :- ::ys/registration_id]
              :body [relocate-request ::ys/relocate-request]
              :return ::ys/response
              (let [session-oid (get-in session [:identity :oid])]
                (if (= oid session-oid)
                  (let [to-exam-session-id (:to_exam_session_id relocate-request)
                        organizer-oid (exam-session-db/get-exam-session-organizer-oid db registration-id)
                        success?           (exam-session-db/update-registration-exam-session! db to-exam-session-id registration-id organizer-oid)]
                    (if success?
                      (ok {:success true})
                      (ok {:success false})))
                  (ok {:success true}))))))))))
