(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.handler.routing :as routing]
            [yki.middleware.auth :as auth]
            [yki.util.audit-log :as audit-log]
            [yki.spec :as ys]
            [pgqueue.core :as pgq]
            [yki.middleware.access-log]
            [clojure.tools.logging :as log]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok bad-request]]
            [ring.util.request]
            [integrant.core :as ig])
  (:import [com.fasterxml.jackson.datatype.joda JodaModule]))

(defn- get-oids [session]
  (map :oid (auth/get-organizations-from-session session)))

(defn- send-delete-reqs-to-queue [data-sync-q oids]
  (doseq [oid oids]
    (pgq/put data-sync-q {:organizer-oid oid
                          :type "DELETE"
                          :created (System/currentTimeMillis)})))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth file-handler exam-session-handler exam-date-handler contact-handler data-sync-q access-log]}]
  {:pre [(some? db) (some? url-helper) (some? auth) (some? file-handler) (some? exam-session-handler) (some? exam-date-handler) (some? contact-handler) (some? data-sync-q) (some? access-log)]}
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
          {:request request
           :target-kv {:k audit-log/organizer, :v (:oid organizer)}
           :change {:type audit-log/create-op, :new organizer}})
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
               (audit-log/log {:request request
                               :target-kv {:k audit-log/organizer
                                           :v oid}
                               :change {:type audit-log/update-op
                                        :old old
                                        :new organizer}})
               (response {:success true}))
             (do
               (log/warn "Organizer not found" oid)
               (not-found {:success false
                           :error "Organizer not found"})))))

       (DELETE "/" request
         :return ::ys/response
         (if (= (organizer-db/delete-organizer! db oid (partial send-delete-reqs-to-queue data-sync-q)) 1)
           (do
             (audit-log/log {:request request
                             :target-kv {:k audit-log/organizer
                                         :v oid}
                             :change {:type audit-log/delete-op}})
             (response {:success true}))
           (do
             (log/warn "Organizer not found" oid)
             (not-found {:success false
                         :error "Organizer not found"}))))
       (context routing/file-uri []
         (file-handler oid))
       (context routing/exam-session-uri []
         (exam-session-handler oid))
       (context routing/exam-date-uri []
         (exam-date-handler oid))
       (context routing/organizer-contact-uri []
         (contact-handler oid))))))
