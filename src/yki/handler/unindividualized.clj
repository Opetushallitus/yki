(ns yki.handler.unindividualized
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.registration-db :as registration-db]
            [ring.util.http-response :refer [ok]]
            [yki.boundary.onr :as onr]
            [clojure.tools.logging :as log]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defn- deep-merge [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defmethod ig/init-key :yki.handler/unindividualized [_ {:keys [db auth onr-client]}]
  (api
    (context routing/unindividualized-uri []
      :middleware [auth]
      (GET "/" []
        (let [registrations (registration-db/get-email-registrations db)
              sanitized-registrations (reduce 
                                        #(assoc %1 (:person_oid %2) 
                                                (assoc {} "email" (-> %2 :form :email) 
                                                          "first_name" (-> %2 :form :first_name)
                                                          "last_name" (-> %2 :form :last_name)
                                                          "exam_lang" (-> %2 :language_code)
                                                          "exam_level" (-> %2 :level_code)
                                                          "exam_date" (-> %2 :exam_date)
                                                          "exam_location_name" (-> %2 :exam_session_location_name))) 
                                        {}
                                        registrations)
              reg-oids-partition (partition 5000 5000 nil (map :person_oid registrations))]
          (if (> (count reg-oids-partition) 0)
            (let [master-henkilot (map #(onr/get-master-oids onr-client %) reg-oids-partition) ;call onr with all partitions
                  master-henkilot-flattened (if (> (count reg-oids-partition) 1) ;flatten to one level if more than 1 partition
                                                (apply concat master-henkilot) 
                                                (first master-henkilot))
                  unindividualized-master-henkilot (into {} 
                                                      (filter #(-> % val (get "yksiloity") (= false)) ;filter individualized out
                                                              master-henkilot-flattened))]
              (ok {:unindividualized (deep-merge sanitized-registrations unindividualized-master-henkilot)}))
            (ok {:unindividualized {}})))))))
