(ns yki.handler.exam_session
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.organizer_db :as organizer-db]
            [ring.util.response :refer [response]]
            [clojure.tools.logging :refer [info error]]
            [ring.util.http-response :refer [bad-request]]
            [ring.util.request]
            [yki.spec :as ys]
            [clojure.spec.alpha :as s]
            [ring.middleware.multipart-params :as mp]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db file-store]}]
  (fn [oid]
    (context "/" []
      (POST "/" []
        :body [exam-session ::ys/exam-session]
        :return ::ys/success-map
        (println "exam-session")
        (response {:success

                   true})))))

