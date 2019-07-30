(ns yki.handler.unindividualized
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.registration-db :as registration-db]
            [ring.util.http-response :refer [ok]]
            [yki.boundary.onr :as onr]
            [clojure.tools.logging :as log]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/unindividualized [_ {:keys [db auth onr-client]}]
  (api
    (context routing/unindividualized-uri []
      :middleware [auth]
      (GET "/" []
        (let [registrations (registration-db/get-email-registrations db)
              reg-oids (map :person_oid registrations)]
          (if (> (count reg-oids) 0)
            (let [master-henkilot (onr/get-master-oids onr-client reg-oids)]
                ;  master-henkilot-parsed (map #(select-keys % ["oidHenkilo" "yksiloity" "etunimet" "sukunimi"]) master-henkilot)]
              (println master-henkilot)
              (ok {:unindividualized master-henkilot}))
            (ok {:unindividualized '()})))))))
