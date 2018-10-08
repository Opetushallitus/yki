(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.handler.routing :as routing]
            [yki.middleware.auth :as auth]
            [yki.util.audit-log :as audit-log]
            [yki.spec :as ys]
            [yki.middleware.access-log :as access-log]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok bad-request]]
            [ring.util.request]
            [integrant.core :as ig])
  (:import [com.fasterxml.jackson.datatype.joda JodaModule]))

(defn- get-oids [session]
  (map #(:oid %) (auth/get-organizations-from-session session)))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth file-handler exam-session-handler]}]
  (api
   (context routing/organizer-api-root []
     :middleware [auth access-log/with-logging]
     :coercion :spec
     (POST "/" request
       :body [organizer ::ys/organizer-type]
       :return ::ys/response
       (if (organizer-db/create-organizer! db organizer)
         (do
           (audit-log/log {:request request
                           :target-kv {:k audit-log/organizer
                                       :v (:oid organizer)}
                           :change {:type audit-log/create-op
                                    :new organizer}})
           (response {:success true}))))

     (GET "/" {session :session}
       :return ::ys/organizers-response
       (if (auth/oph-user? session)
         (response {:organizers (organizer-db/get-organizers db)})
         (response {:organizers (organizer-db/get-organizers-by-oids db (get-oids session))})))

     (context "/:oid" [oid]
       (PUT "/" request
         :body [organizer ::ys/organizer-type]
         :return ::ys/response
         (if (= (organizer-db/update-organizer! db oid organizer) 1)
           (let [current (first (organizer-db/get-organizers-by-oids db [oid]))]
             (audit-log/log {:request request
                             :target-kv {:k audit-log/organizer
                                         :v oid}
                             :change {:type audit-log/update-op
                                      :old current
                                      :new organizer}})
             (response {:success true}))
           (not-found {:success false
                       :error "Organizer not found"})))

       (DELETE "/" request
         :return ::ys/response
         (if (= (organizer-db/delete-organizer! db oid) 1)
           (do
             (audit-log/log {:request request
                             :target-kv {:k audit-log/organizer
                                         :v oid}
                             :change {:type audit-log/delete-op}})
             (response {:success true}))
           (not-found {:success false
                       :error "Organizer not found"})))
       (context routing/file-uri []
         (file-handler oid))
       (context routing/exam-session-uri []
         (exam-session-handler oid))))))
