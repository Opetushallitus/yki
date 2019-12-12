(ns yki.api
  (:require [compojure.api.sweet :refer [api routes]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki/api [_ handlers]
  (api
   {:swagger {:ui "/yki/api/docs", :spec "/yki/api/swagger.json"}}
   (apply routes handlers)))
