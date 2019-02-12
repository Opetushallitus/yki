(ns yki.handler.exam-session
  (:require [compojure.api.sweet :refer :all]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.handler.routing :as routing]
            [yki.util.audit-log :as audit-log]
            [pgqueue.core :as pgq]
            [ring.util.response :refer [response not-found]]
            [clojure.tools.logging :refer [info error]]
            [ring.util.http-response :refer [bad-request]]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defn- send-to-queue [data-sync-q exam-session type]
  #(pgq/put data-sync-q  {:type type
                          :exam-session exam-session
                          :created (System/currentTimeMillis)}))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db data-sync-q email-q url-helper]}]
  {:pre [(some? db) (some? data-sync-q) (some? email-q) (some? url-helper)]}
  (fn [oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date nil}]
        :return ::ys/exam-sessions-response
        (response {:exam_sessions (exam-session-db/get-exam-sessions db oid from)}))
      (POST "/" request
        :body [exam-session ::ys/exam-session]
        :return ::ys/id-response
        (if-let [exam-session-id (exam-session-db/create-exam-session! db
                                                                       oid
                                                                       exam-session
                                                                       (send-to-queue data-sync-q exam-session "CREATE"))]
          (do
            (audit-log/log {:request request
                            :target-kv {:k audit-log/exam-session
                                        :v exam-session-id}
                            :change {:type audit-log/create-op
                                     :new exam-session}})
            (response {:id exam-session-id}))))

      (context "/:id" []
        (PUT "/" request
          :body [exam-session ::ys/exam-session]
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [current (exam-session-db/get-exam-session-by-id db id)]
            (if (exam-session-db/update-exam-session! db oid id exam-session (send-to-queue data-sync-q exam-session "UPDATE"))
              (do
                (audit-log/log {:request request
                                :target-kv {:k audit-log/exam-session
                                            :v id}
                                :change {:type audit-log/update-op
                                         :old current
                                         :new exam-session}})
                (response {:success true}))
              (not-found {:success false
                          :error "Exam session not found"}))))
        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-session (exam-session-db/get-exam-session-by-id db id)]
            (if (exam-session-db/delete-exam-session! db id oid (send-to-queue data-sync-q exam-session "DELETE"))
              (do
                (audit-log/log {:request request
                                :target-kv {:k audit-log/exam-session
                                            :v id}
                                :change {:type audit-log/delete-op}})
                (response {:success true}))
              (not-found {:success false
                          :error "Exam session not found"}))))

        (context routing/registration-uri []
          (GET "/" {session :session}
            :path-params [id :- ::ys/id]
            :return ::ys/participants-response
            (response {:participants (exam-session-db/get-exam-session-participants db id oid)}))
          (context "/:registration-id" []
            (DELETE "/" request
              :path-params [registration-id :- ::ys/id]
              :return ::ys/response
              (if (exam-session-db/set-registration-status-to-cancelled! db registration-id oid)
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/registration
                                              :v registration-id}
                                  :change {:type audit-log/delete-op}})
                  (response {:success true}))
                (not-found {:success false
                            :error "Registration not found"})))
            (POST "/relocate" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
              :body [relocate-request ::ys/relocate-request]
              :return ::ys/response
              (if (exam-session-db/update-registration-exam-session! db (:to_exam_session_id relocate-request) registration-id oid)
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/registration
                                              :v registration-id}
                                  :change {:type audit-log/update-op
                                           :old {:exam_session_id id}
                                           :new {:exam_session_id (:to_exam_session_id relocate-request)}}})
                  (response {:success true}))
                (not-found {:success false
                            :error "Registration not found"})))
            (POST "/confirm-payment" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
              :body [confirm-payment-request ::ys/confirm-payment-request]
              :return ::ys/response
              (let [payment (registration-db/get-payment-by-registration-id db registration-id oid)]
                (if payment
                  (do
                    (paytrail-payment/handle-payment-success db email-q url-helper {:order-number (:order_number payment)})
                    (audit-log/log {:request request
                                    :target-kv {:k audit-log/registration
                                                :v registration-id}
                                    :change {:type audit-log/update-op
                                             :old {:payment_state "UNPAID"}
                                             :new {:payment_state (:state confirm-payment-request)}}})
                    (response {:success true}))
                  (not-found {:success false
                              :error "Payment not found"}))))))))))

