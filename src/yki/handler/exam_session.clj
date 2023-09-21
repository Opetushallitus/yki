(ns yki.handler.exam-session
  (:require
    [clj-time.core :as t]
    [clj-time.coerce :as c]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [pgqueue.core :as pgq]
    [ring.util.http-response :refer [conflict internal-server-error ok]]
    [ring.util.response :refer [bad-request not-found response]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.handler.routing :as routing]
    [yki.middleware.auth :as auth]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit-log]
    [yki.util.common :refer [string->date]]
    [yki.registration.email :as registration-email]
    [yki.boundary.registration-db :as registration-db]))

(defn- send-to-queue [data-sync-q exam-session type]
  #(pgq/put data-sync-q {:type         type
                         :exam-session exam-session
                         :created      (System/currentTimeMillis)}))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db data-sync-q email-q pdf-renderer url-helper]}]
  {:pre [(some? db) (some? data-sync-q) (some? email-q) (some? pdf-renderer) (some? url-helper)]}
  (fn [oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date-type nil}]
        :return ::ys/exam-sessions-response
        (let [from-date (string->date from)]
          (response {:exam_sessions (exam-session-db/get-exam-sessions db oid from-date)})))

      (POST "/" request
        :body [exam-session ::ys/exam-session]
        :return ::ys/id-response
        (when-let [exam-session-id (exam-session-db/create-exam-session!
                                     db oid exam-session
                                     (send-to-queue
                                       data-sync-q
                                       (assoc exam-session :organizer_oid oid)
                                       "CREATE"))]
          (audit-log/log {:request   request
                          :target-kv {:k audit-log/exam-session
                                      :v exam-session-id}
                          :change    {:type audit-log/create-op
                                      :new  exam-session}})
          (response {:id exam-session-id})))

      (context "/:id" []
        (PUT "/" request
          :body [exam-session ::ys/exam-session]
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [current          (exam-session-db/get-exam-session-by-id db id)
                participants     (:participants current)
                max-participants (:max_participants exam-session)]
            (if (<= participants max-participants)
              (if (exam-session-db/update-exam-session! db oid id exam-session)
                (do
                  (audit-log/log {:request   request
                                  :target-kv {:k audit-log/exam-session
                                              :v id}
                                  :change    {:type audit-log/update-op
                                              :old  current
                                              :new  exam-session}})
                  (response {:success true}))
                (not-found {:success false
                            :error   "Exam session not found"}))
              (do
                (log/error "Max participants" max-participants "less than current participants" participants)
                (conflict {:error "Max participants less than current participants"})))))

        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-session   (exam-session-db/get-exam-session-by-id db id)
                participants   (when exam-session (:participants exam-session))
                reg-start-date (when exam-session (c/from-long (:registration_start_date exam-session)))]
            (log/info "Deleting exam session:" id)
            (cond
              (nil? exam-session) (do (log/error "Could not find exam session" id)
                                      (not-found {:success false
                                                  :error   "Exam session not found"}))
              (> participants 0) (do (log/error "Cannot delete exam session with participants" id)
                                     (conflict {:success false
                                                :error   "Cannot delete exam session with participants"}))
              (t/after? (t/minus (t/now) (t/days 1)) reg-start-date) (do (log/error "Cannot delete exam session after registration start date" id)
                                                                         (conflict {:success false
                                                                                    :error   "Cannot delete exam session after registration start date"}))
              :else (if (exam-session-db/delete-exam-session! db id oid (send-to-queue
                                                                          data-sync-q
                                                                          (assoc exam-session :organizer_oid oid)
                                                                          "DELETE"))
                      (do
                        (audit-log/log {:request   request
                                        :target-kv {:k audit-log/exam-session
                                                    :v id}
                                        :change    {:type audit-log/delete-op}})
                        (response {:success true}))
                      (do (log/error "Error occurred when deleting exam session" id)
                          (not-found {:success false
                                      :error   "Exam session not found"}))))))

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
              (< (:post_admission_quota activation) 1) (do (log/error "Attempting to set too small quota of" (:post_admission_quota activation) "for exam session" id)
                                                           (conflict {:success false :error "Minimum quota for post admission is 1"}))
              :else
              (if (exam-session-db/set-post-admission-active! db id (:post_admission_quota activation))
                (response {:success true})
                (do
                  (log/error "Error occurred when attempting to activate post admission for exam session" id)
                  (internal-server-error {:success false
                                          :error   "Could not activate post admission"}))))))

        (POST (str routing/post-admission-uri "/deactivate") request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-session (exam-session-db/get-exam-session-by-id db id)]
            (if exam-session
              (if (exam-session-db/set-post-admission-deactive! db id)
                (response {:success true})
                (do
                  (log/error "Error occurred when attempting to deactivate post admission for exam session" id)
                  (internal-server-error {:success false
                                          :error   "Could not deactivate post admission"})))
              (do (log/error "Could not find exam session with id" id)
                  (not-found {:success false
                              :error   "Exam session not found"})))))

        (context routing/registration-uri []
          (GET "/" {session :session}
            :path-params [id :- ::ys/id]
            :return ::ys/participants-response
            (response {:participants (exam-session-db/get-exam-session-participants db id oid)}))
          (context "/:registration-id" []
            (DELETE "/" request
              :path-params [registration-id :- ::ys/id]
              :return ::ys/response
              (let [cancelled-registration
                    (if (auth/oph-admin-access request)
                      (exam-session-db/cancel-registration! db registration-id)
                      ; If user is not an OPH-admin but rather an exam organizer, only allow cancelling unpaid registrations
                      (exam-session-db/cancel-unpaid-registration! db registration-id oid))]
                (if cancelled-registration
                  (do
                    (audit-log/log {:request   request
                                    :target-kv {:k audit-log/registration
                                                :v registration-id}
                                    :change    {:type audit-log/delete-op}})
                    (response {:success true}))
                  (bad-request {:success false
                                :error   "Registration couldn't be cancelled"}))))
            (POST "/relocate" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
              :body [relocate-request ::ys/relocate-request]
              :return ::ys/response
              (log/info "Start relocating registration" registration-id "from session" id "to session" (:to_exam_session_id relocate-request))
              (let [to-exam-session-id (:to_exam_session_id relocate-request)
                    success?           (exam-session-db/update-registration-exam-session! db to-exam-session-id registration-id oid)]
                (if success?
                  (do
                    (audit-log/log {:request   request
                                    :target-kv {:k audit-log/registration
                                                :v registration-id}
                                    :change    {:type audit-log/update-op
                                                :old  {:exam_session_id id}
                                                :new  {:exam_session_id (:to_exam_session_id relocate-request)}}})
                    ; Sync only the relocation destination exam session
                    (exam-session-db/init-relocated-participants-sync-status! db to-exam-session-id)
                    (response {:success true}))
                  (not-found {:success false
                              :error   "Registration not found"}))))
            (POST "/resend-confirmation-email" _
              :path-params [id :- ::ys/id
                            registration-id :- ::ys/id]
              :query-params [lang :- ::ys/language-code]
              :return ::ys/response
              (if-let [registration-details (registration-db/get-completed-registration-data db id registration-id lang)]
                (if-let [payment-details (registration-db/get-completed-payment-data-for-registration db registration-id)]
                  (let [exam-session-contact-info      (exam-session-db/get-contact-info-by-exam-session-id db id)
                        exam-session-extra-information (exam-session-db/get-exam-session-location-extra-information db id lang)
                        email-template-data            (assoc registration-details
                                                         :contact_info exam-session-contact-info
                                                         :extra_information (:extra_information exam-session-extra-information))]
                    (log/info "Resending confirmation email for registration with id" registration-id)
                    (registration-email/send-exam-registration-completed-email!
                      email-q
                      pdf-renderer
                      lang
                      email-template-data
                      payment-details)
                    (ok {:success true}))
                  (do
                    (log/error "Could not resend confirmation email. No completed payment corresponding to registration found."
                               {:registration-id registration-id})
                    (bad-request {:success false
                                  :error   :payment-not-found})))
                (do
                  (log/error "Could not resend confirmation email. No completed registration found for exam session."
                             {:exam-session-id id
                              :registration-id registration-id})
                  (bad-request {:success false
                                :error   :registration-not-found}))))))))))
