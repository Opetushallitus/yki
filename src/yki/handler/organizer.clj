(ns yki.handler.organizer
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.handler.routing :as routing]
            [yki.middleware.auth :as auth]
            [yki.spec :as ys]
            [yki.middleware.access-log :as access-log]
            [clojure.tools.logging :refer [info error]]
            [ring.util.response :refer [response not-found header]]
            [ring.util.http-response :refer [ok bad-request]]
            [ring.util.request]
            [integrant.core :as ig]))

(defn- get-oids [session]
  (map #(:oid %) (auth/get-organizations-from-session session)))

(defmethod ig/init-key :yki.handler/organizer [_ {:keys [db url-helper auth file-handler exam-session-handler]}]
  (api
   (context routing/organizer-api-root []
     :middleware [auth access-log/with-logging]
     :coercion :spec
     (POST "/" []
       :body [organizer ::ys/organizer-type]
       :return ::ys/response
       (if (organizer-db/create-organizer! db organizer)
         (response {:success true})))
     (GET "/" {session :session}
       :return ::ys/organizers-response
       (if (auth/oph-user? session)
         (response {:organizers (organizer-db/get-organizers db)})
         (response {:organizers (organizer-db/get-organizers-by-oids db (get-oids session))})))
     (context "/:oid" [oid]
       (PUT "/" []
         :body [organizer ::ys/organizer-type]
         :return ::ys/response
         (if (organizer-db/update-organizer! db oid organizer)
           (response {:success true})
           (not-found {:success false
                       :error "Organizer not found"})))
       (DELETE "/" []
         :return ::ys/response
         (if (= (organizer-db/delete-organizer! db oid) 1)
           (response {:success true})
           (not-found {:error "Organizer not found"})))
       (context routing/file-uri []
         (file-handler oid))
       (context routing/exam-session-uri []
         (exam-session-handler oid))))))
