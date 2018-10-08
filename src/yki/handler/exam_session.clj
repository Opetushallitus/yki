(ns yki.handler.exam-session
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.util.audit-log :as audit-log]
            [ring.util.response :refer [response not-found]]
            [clojure.tools.logging :refer [info error]]
            [ring.util.http-response :refer [bad-request]]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db]}]
  (fn [oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date nil}]
        :return ::ys/exam-sessions-response
        (response {:exam_sessions (exam-session-db/get-exam-sessions db oid from)}))
      (POST "/" request
        :body [exam-session ::ys/exam-session]
        :return ::ys/id-response
        (try
          (if-let [exam-session-id (exam-session-db/create-exam-session! db oid exam-session)]
            (do
              (audit-log/log {:request request
                              :target-kv {:k audit-log/exam-session
                                          :v exam-session-id}
                              :change {:type audit-log/create-op
                                       :new exam-session}})
              (response {:id exam-session-id})))
          (catch Exception e
            (error e "Creating exam session failed")
            (throw e))))
      (context "/:id" []
        (PUT "/" request
          :body [exam-session ::ys/exam-session]
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (exam-session-db/update-exam-session! db id exam-session)
            (let [current (exam-session-db/get-exam-session-by-id db id)]
              (audit-log/log {:request request
                              :target-kv {:k audit-log/exam-session
                                          :v id}
                              :change {:type audit-log/update-op
                                       :old current
                                       :new exam-session}})
              (response {:success true}))
            (not-found {:success false
                        :error "Exam session not found"})))
        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (= (exam-session-db/delete-exam-session! db id) 1)
            (do
              (audit-log/log {:request request
                              :target-kv {:k audit-log/exam-session
                                          :v id}
                              :change {:type audit-log/delete-op}})
              (response {:success true}))
            (not-found {:success false
                        :error "Exam session not found"})))))))

