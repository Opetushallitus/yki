(ns yki.handler.exam_session
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [ring.util.response :refer [response]]
            [taoensso.timbre :as timbre :refer [info error]]
            [ring.util.http-response :refer [bad-request]]
            [ring.util.request]
            [yki.spec :as ys]
            [ring.middleware.multipart-params :as mp]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db file-store]}]
  (fn [oid]
    (context "/" []
      (POST "/" []
        :body [exam-session ::ys/exam-session]
        :return ::ys/response
        (try
          (if (exam-session-db/create-exam-session! db exam-session)
            (response {:success true}))
          (catch Exception e
            (error e "Creating exam session failed")
            (throw e)))))))

