(ns yki.handler.organizer
  (:require [clojure.tools.logging :as log]
            [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
            [integrant.core :as ig]
            [pgqueue.core :as pgq]
            [ring.util.response :refer [response not-found]]
            [ring.util.request]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.handler.routing :as routing]
            [yki.middleware.access-log]
            [yki.middleware.auth :as auth]
            [yki.spec :as ys]
            [yki.util.audit-log :as audit-log]))

(defn- get-oids [session]
  (map :oid (auth/get-organizations-from-session session)))

(defn- send-delete-reqs-to-queue [data-sync-q oids]
  (doseq [oid oids]
    (pgq/put data-sync-q {:organizer-oid oid
                          :type          "DELETE"
                          :created       (System/currentTimeMillis)})))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth file-handler exam-session-handler exam-date-handler data-sync-q access-log]}]
  {:pre [(some? db) (some? url-helper) (some? auth) (some? file-handler) (some? exam-session-handler) (some? exam-date-handler) (some? data-sync-q) (some? access-log)]}
  (api
    (context routing/organizer-api-root []
      :middleware [auth access-log]
      :coercion :spec
      (POST "/" request
        :body [organizer ::ys/organizer-type]
        :return ::ys/response
        (log/info "creating organizer" organizer)
        (when (organizer-db/create-organizer! db organizer)
          (audit-log/log
            {:request   request
             :target-kv {:k audit-log/organizer, :v (:oid organizer)}
             :change    {:type audit-log/create-op, :new organizer}})
          (response {:success true})))
      (GET "/" {session :session}
        :return ::ys/organizers-response
        (if (auth/oph-admin? (auth/get-organizations-from-session session))
          (response {:organizers (organizer-db/get-organizers db)})
          (response {:organizers (organizer-db/get-organizers-by-oids db (get-oids session))})))
      (context "/:oid" [oid]
        (PUT "/" request
          :body [organizer ::ys/organizer-type]
          :return ::ys/response
          (let [old (first (organizer-db/get-organizers-by-oids db [oid]))]
            (log/info "Updating organizer" old oid)
            (if (= (organizer-db/update-organizer! db oid organizer) 1)
              (do
                (audit-log/log {:request   request
                                :target-kv {:k audit-log/organizer
                                            :v oid}
                                :change    {:type audit-log/update-op
                                            :old  old
                                            :new  organizer}})
                (response {:success true}))
              (do
                (log/warn "Organizer not found" oid)
                (not-found {:success false
                            :error   "Organizer not found"})))))

        (DELETE "/" request
          :return ::ys/response
          (if (= (organizer-db/delete-organizer! db oid (partial send-delete-reqs-to-queue data-sync-q)) 1)
            (do
              (audit-log/log {:request   request
                              :target-kv {:k audit-log/organizer
                                          :v oid}
                              :change    {:type audit-log/delete-op}})
              (response {:success true}))
            (do
              (log/warn "Organizer not found" oid)
              (not-found {:success false
                          :error   "Organizer not found"}))))
        (context routing/file-uri []
          (file-handler oid))
        (context routing/exam-session-uri []
          (exam-session-handler oid))
        (context routing/exam-date-uri []
          (exam-date-handler oid))))))
