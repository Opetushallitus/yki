(ns yki.handler.exam-session-public
  (:require [compojure.api.sweet :refer [context GET POST]]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.post-admission-db :as post-admission-db]
            [yki.handler.routing :as routing]
            [ring.util.http-response :refer [ok not-found conflict internal-server-error]]
            [ring.util.request]
            [yki.spec :as ys]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn- get-exam-fee
  [payment-config exam-session]
  (get-in payment-config [:amount (keyword (:level_code exam-session))]))

(defmethod ig/init-key :yki.handler/exam-session-public [_ {:keys [db payment-config]}]
  {:pre [(some? db) (some? payment-config)]}
  (context routing/exam-session-public-api-root []
    :coercion :spec
    (GET "/" []
      :query-params [{from :- ::ys/date nil}]
      :return ::ys/exam-sessions-response
      (let [exam-sessions (exam-session-db/get-exam-sessions db nil from)
            with-fee (map #(assoc % :exam_fee (get-exam-fee payment-config %)) exam-sessions)]
        (ok {:exam_sessions with-fee})))
    (context "/:id" []
      (GET "/" []
        :return ::ys/exam-session
        :path-params [id :- ::ys/id]
        (if-let [exam-session (exam-session-db/get-exam-session-by-id db id)]
          (ok (assoc exam-session :exam_fee (get-exam-fee payment-config exam-session)))
          (not-found "Exam session not found")))
      (POST "/queue" []
        :path-params [id :- ::ys/id]
        :query-params [lang :- ::ys/language-code]
        :body [request ::ys/to-queue-request]
        :return ::ys/response
        (log/info "Adding email " (:email request) " to exam session " id " queue")
        (if (exam-session-db/get-email-added-to-queue? db (:email request) id)
          (conflict {:exists true})
          (do
            (exam-session-db/add-to-exam-session-queue! db (:email request) lang id)
            (ok {:success true}))))
      (POST "/post-admission" []
        :path-params [id :- ::ys/exam_session_id]
        :body [post-admission ::ys/post-admission-request]
        ;:return ::ys/id-response
        (if (post-admission-db/upsert-post-admission db post-admission id)
          (ok {:success true})
          (internal-server-error))))))

