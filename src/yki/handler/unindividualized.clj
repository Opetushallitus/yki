(ns yki.handler.unindividualized
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.registration-db :as registration-db]
            [ring.util.http-response :refer [ok]]
            [yki.boundary.onr :as onr]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(def select-values (comp vals select-keys))

(defmethod ig/init-key :yki.handler/unindividualized [_ {:keys [db auth onr-client]}]
  (api
    (context routing/unindividualized-uri []
      :middleware [auth]
      (GET "/" []
        (let [registrations (registration-db/get-email-registrations db)
              ; master-oids-response (onr/get-master-oids onr-client registrations)
              ]
          (println (map (select-keys [:person_oid]) registrations))
          (ok {:unindividualized registrations}))))))
