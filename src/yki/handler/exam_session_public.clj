(ns yki.handler.exam-session-public
  (:require
    [clj-time.core :as t]
    [clojure.spec.alpha :as s]
    [compojure.api.sweet :refer [context GET]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok not-found]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.spec :as ys]))

(defn- get-exam-fee
  [payment-config exam-session]
  (get-in payment-config [:amount (keyword (:level_code exam-session))]))

(defmethod ig/init-key :yki.handler/exam-session-public [_ {:keys [db environment payment-config]}]
  {:pre [(some? db) (s/valid? ::ys/environment environment) (some? payment-config)]}
  (context routing/exam-session-public-api-root []
    :middleware [with-error-boundary]
    :coercion
    (when-not (= :prod environment)
      :spec)
    (GET "/" []
      :return ::ys/exam-sessions-response
      (let [from-date     (t/now)
            exam-sessions (exam-session-db/get-exam-sessions db from-date)
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
          (not-found "Exam session not found"))))))

