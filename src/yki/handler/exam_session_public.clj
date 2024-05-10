(ns yki.handler.exam-session-public
  (:require
    [clj-time.core :as t]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [context GET POST]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok not-found conflict]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.handler.routing :as routing]
    [yki.spec :as ys]))

(defn- get-exam-fee
  [payment-config exam-session]
  (get-in payment-config [:amount (keyword (:level_code exam-session))]))

(defmethod ig/init-key :yki.handler/exam-session-public [_ {:keys [db environment payment-config]}]
  {:pre [(some? db) (s/valid? ::ys/environment environment) (some? payment-config)]}
  (context routing/exam-session-public-api-root []
    :coercion
    (when-not (= :prod environment)
      :spec)
    (GET "/" []
      :return ::ys/exam-sessions-response
      (let [from-date     (t/now)
            exam-sessions (exam-session-db/get-exam-sessions db nil from-date)
            with-fee      (map #(assoc % :exam_fee (get-exam-fee payment-config %)) exam-sessions)]
        (ok {:exam_sessions with-fee})))

    (context "/:id" []
      :coercion :spec
      (GET "/" []
        :return ::ys/exam-session
        :path-params [id :- ::ys/id]
        (if-let [exam-session (exam-session-db/get-exam-session-by-id db id)]
          (-> (assoc exam-session :exam_fee (get-exam-fee payment-config exam-session))
              (ok))
          (not-found "Exam session not found")))

      (POST "/queue" []
        :path-params [id :- ::ys/id]
        :query-params [lang :- ::ys/language-code]
        :body [request ::ys/to-queue-request]
        :return ::ys/response
        (let [result (exam-session-db/add-to-exam-session-queue! db (:email request) lang id)]
          (if (:success result)
            (do
              (log/info "Adding email" (:email request) "to exam session" id "queue")
              (ok result))
            (do
              (log/warn "Failed to add" (:email request) "to exam session" id "queue. Result:" result)
              (conflict result))))))))

