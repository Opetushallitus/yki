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
  (let [code-from-config (fn [code] (get-in payment-config [:amount code]))]
    (case (:type exam-session)
      "FULL"         {:exam_fee (code-from-config (keyword (:level_code exam-session)))}
      "READ_SPEAK"   {:exam_fee (+ (code-from-config :KESKI_READ)
                                   (code-from-config :KESKI_SPEAK))
                      :exam_fee_read_listen (code-from-config :KESKI_READ)
                      :exam_fee_speak_write (code-from-config :KESKI_SPEAK)}
      "LISTEN_WRITE" {:exam_fee (+ (code-from-config :KESKI_LISTEN)
                                   (code-from-config :KESKI_WRITE))
                      :exam_fee_read_listen (code-from-config :KESKI_LISTEN)
                      :exam_fee_speak_write (code-from-config :KESKI_WRITE)})))

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
            with-fee      (map #(merge % (get-exam-fee payment-config %)) exam-sessions)]
        (ok {:exam_sessions with-fee})))

    (context "/:id" []
      :coercion :spec
      (GET "/" []
        :return ::ys/exam-session
        :path-params [id :- ::ys/id]
        (if-let [exam-session (exam-session-db/get-exam-session-by-id db id)]
          (-> (merge exam-session (get-exam-fee payment-config exam-session))
              (ok))
          (not-found "Exam session not found"))))))
