(ns yki.handler.registration
  (:require [compojure.api.sweet :refer :all]
            [yki.handler.routing :as routing]
            [yki.spec :as ys]
            [ring.util.http-response :refer [ok]]
            [integrant.core :as ig]))


          
(defmethod ig/init-key :yki.handler/registration [_ {:keys [db auth access-log]}]
  (api
   (context routing/registration-root []
     :coercion :spec
     :middleware [auth access-log]
     (POST "/init" request
       :body [registration-init ::ys/registration-init]
       :query-params [lang :- ::ys/language_code]
       :return ::ys/response
           (ok {:success true})))))
