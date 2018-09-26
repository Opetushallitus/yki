(ns yki.handler.exam_session
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [ring.util.response :refer [response not-found header]]
            [taoensso.timbre :as timbre :refer [info error]]
            [ring.util.http-response :refer [bad-request]]
            [ring.util.request]
            [yki.spec :as ys]
            [ring.middleware.multipart-params :as mp]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db]}]
  (fn [oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date nil}]
        :return ::ys/exam-sessions-response
        (response {:exam_sessions (exam-session-db/get-exam-sessions db oid from)}))
      (POST "/" []
        :body [exam-session ::ys/exam-session]
        :return ::ys/id-response
        (try
          (if-let [exam-session-id (exam-session-db/create-exam-session! db oid exam-session)]
            (response {:id exam-session-id}))
          (catch Exception e
            (error e "Creating exam session failed")
            (throw e))))
      (context "/:id" []
        (PUT "/" []
          :body [exam-session ::ys/exam-session]
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (exam-session-db/update-exam-session! db id exam-session)
            (response {:success true})
            (not-found {:success false
                        :error "Exam session not found"})))
        (DELETE "/" []
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (exam-session-db/delete-exam-session! db id)
            (response {:success true})
            (not-found {:success false
                        :error "Exam session not found"})))))))

