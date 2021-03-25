(ns yki.handler.exam-session
  (:require [compojure.api.sweet :refer [api context GET POST PUT DELETE]]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.boundary.registration-db :as registration-db]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.registration.registration :as registration]
            [yki.handler.routing :as routing]
            [yki.util.audit-log :as audit-log]
            [pgqueue.core :as pgq]
            [ring.util.response :refer [response not-found]]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [conflict internal-server-error ok]]
            [ring.util.request]
            [yki.spec :as ys]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
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
        :query-params [{from :- ::ys/date-type nil} {days :- ::ys/days nil}]
        :return ::ys/exam-sessions-response
        (let [from-date  (if from (c/from-long from) (t/now))
              history-date (if days (-> from-date
                                        (t/minus (t/days days))) from-date)
              new-from-date (f/unparse (f/formatter "yyyy-MM-dd") history-date)
              sessions (exam-session-db/get-exam-sessions db oid new-from-date)]
          (response {:exam_sessions sessions})))

      (POST "/" request
        :body [exam-session ::ys/exam-session]
        :return ::ys/id-response
        (log/info "Create exam sessio")
        (log/info "" exam-session)
        (if-let [exam-session-id (exam-session-db/create-exam-session! db oid exam-session
                                                                       (send-to-queue
                                                                        data-sync-q
                                                                        (assoc exam-session :organizer_oid oid)
                                                                        "CREATE"))]
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
          (let [current (exam-session-db/get-exam-session-by-id db id)
                participants (:participants current)
                max-participants (:max_participants exam-session)]
            (if (<= participants max-participants)
              (if (exam-session-db/update-exam-session! db oid id exam-session)
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/exam-session
                                              :v id}
                                  :change {:type audit-log/update-op
                                           :old current
                                           :new exam-session}})
                  (response {:success true}))
                (not-found {:success false
                            :error "Exam session not found"}))
              (do
                (log/error "Max participants"  max-participants "less than current participants" participants)
                (conflict {:error "Max participants less than current participants"})))))

        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-session (exam-session-db/get-exam-session-by-id db id)
                participants (when exam-session (:participants exam-session))
                reg-start-date (when exam-session (c/from-long (:registration_start_date exam-session)))]
            (log/info "Deleting exam session: " id)
            (cond
              (nil? exam-session) (do (log/error "Could not find exam session " id)
                                      (not-found {:success false
                                                  :error "Exam session not found"}))
              (> participants 0) (do (log/error "Cannot delete exam session with participants " id)
                                     (conflict {:success false
                                                :error "Cannot delete exam session with participants"}))
              (t/after? (t/minus (t/now) (t/days 1)) reg-start-date) (do (log/error "Cannot delete exam session after registration start date " id)
                                                                         (conflict {:success false
                                                                                    :error "Cannot delete exam session after registration start date "}))
              :else (if (exam-session-db/delete-exam-session! db id oid (send-to-queue
                                                                         data-sync-q
                                                                         (assoc exam-session :organizer_oid oid)
                                                                         "DELETE"))
                      (do
                        (audit-log/log {:request request
                                        :target-kv {:k audit-log/exam-session
                                                    :v id}
                                        :change {:type audit-log/delete-op}})
                        (response {:success true}))
                      (do (log/error "Error occured when deleting exam session " id)
                          (not-found {:success false
                                      :error "Exam session not found"}))))))

        (POST (str routing/post-admission-uri "/activate") request
          :path-params [id :- ::ys/id]
          :body [activation ::ys/post-admission-activation]
          :return ::ys/response
          (let [exam-session (exam-session-db/get-exam-session-by-id db id)]
            (cond
              (= exam-session nil) (do (log/error "Could not find exam session with id" id)
                                       (not-found {:success false :error "Exam session not found"}))
              (= (:post_admission_enabled exam-session) false) (do (log/error "Post admissions are not enabled for exam session" id "with an exam date" (:session_date exam-session))
                                                                   (conflict {:success false :error "Post admissions are not enabled for this exam date"}))
              (< (:post_admission_quota activation) 1) (do (log/error "Attempting to set too small quota of" (:post_admission_quota activation) "for exam session " id)
                                                           (conflict {:success false :error "Minimum quota for post admission is 1"}))
              :else
              (if (exam-session-db/set-post-admission-active! db id (:post_admission_quota activation))
                (response {:success true})
                (do
                  (log/error "Error occured when attempting to activate post admission for exam session" id)
                  (internal-server-error {:success false
                                          :error "Could not activate post admission"}))))))

        (POST (str routing/post-admission-uri "/deactivate") request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-session (exam-session-db/get-exam-session-by-id db id)]
            (if exam-session
              (if (exam-session-db/set-post-admission-deactive! db id)
                (response {:success true})
                (do
                  (log/error "Error occured when attempting to deactivate post admission for exam session" id)
                  (internal-server-error {:success false
                                          :error "Could not deactivate post admission"})))

              (do (log/error "Could not find exam session with id" id)
                  (not-found {:success false :error "Exam session not found"})))))

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


            ; Regarding email confirmation resend: needs to generate a whole new login link and invalidate old one
            ; (POST "/resendConfirmation" request
            ;   :path-params [id :- ::ys/id registration-id :- ::ys/id]
            ;   :query-params [emailLang :- ::ys/language-code]
            ;   :return ::ys/response
            ;   (log/info "id: " id " registration-id: " registration-id " emailLang: " emailLang)
            ;   (if (registration/resend-link db url-helper email-q emailLang id registration-id)
            ;       (response {:success true})
            ;       (not-found {:success false
            ;                  :error "Registration not found"})))


            (POST "/confirm-payment" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
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
                                             :new {:payment_state "PAID"}}})
                    (response {:success true}))
                  (not-found {:success false
                              :error "Payment not found"}))))))))))

