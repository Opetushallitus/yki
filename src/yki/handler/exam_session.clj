(ns yki.handler.exam-session
  (:require
    [clj-time.core :as t]
    [clj-time.coerce :as c]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [context GET POST PUT DELETE]]
    [integrant.core :as ig]
    [pgqueue.core :as pgq]
    [ring.util.http-response :refer [conflict ok]]
    [ring.util.response :refer [bad-request not-found response]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.handler.routing :as routing]
    [yki.spec :as ys]
    [yki.util.audit-log :as audit-log]
    [yki.util.common :refer [string->date]]
    [yki.registration.email :as registration-email]
    [yki.registration.registration :as registration]
    [yki.boundary.registration-db :as registration-db]))

(defn- send-to-queue [data-sync-q exam-session type]
  #(pgq/put data-sync-q {:type         type
                         :exam-session exam-session
                         :created      (System/currentTimeMillis)}))

(defn- prepare-for-audit-logging [exam-session]
  ; Updates to location data of exam sessions seem to cause
  ; performance issues with the algorithm used for generating diffs
  ; for audit logging as implemented by clj-json-patch.
  ;
  ; Taking diffs of even simple sequential collections of maps seems
  ; to trigger an exponential slowdown with the patch generation algorithm.
  ; Let us sidestep the issue by converting the locations array to
  ; a map of language to location when sending events to audit log.
  ; As a bonus, this should help in interpreting the audit log events.
  (let [locations      (:location exam-session)
        lang->location (into {} (map (juxt :lang identity)) locations)]
    (assoc exam-session :location lang->location)))

(defmethod ig/init-key :yki.handler/exam-session [_ {:keys [db data-sync-q email-q pdf-renderer url-helper]}]
  {:pre [(some? db) (some? data-sync-q) (some? email-q) (some? pdf-renderer) (some? url-helper)]}
  (fn [oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date-type nil}]
        :return ::ys/exam-sessions-response
        (let [from-date (string->date from)]
          (response {:exam_sessions (exam-session-db/get-exam-sessions-for-oid db oid from-date)})))

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
                ; Read updated session details anew from the database to ensure
                ; we record the actual logical changes into the audit log.
                ; Note that this allows for a potential race condition
                ; and a subsequent loss of audit trail in case multiple
                ; updates happen during a short time span.
                ;
                ; As the update operation spans multiple tables,
                ; we can't just rely on the JDBC driver returning a single updated
                ; row and the current approach seems to be the best currently available
                ; option for ensuring a reasonable audit log entry.
                (let [updated-session (exam-session-db/get-exam-session-by-id db id)]
                  (audit-log/log {:request   request
                                  :target-kv {:k audit-log/exam-session
                                              :v id}
                                  :change    {:type audit-log/update-op
                                              :old  (prepare-for-audit-logging current)
                                              :new  (prepare-for-audit-logging updated-session)}})
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

        (context routing/registration-uri []
          (GET "/" {session :session}
            :path-params [id :- ::ys/id]
            :return ::ys/participants-response
            (response {:participants (exam-session-db/get-exam-session-participants db id oid)}))
          (context "/:registration-id" []
            (DELETE "/" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
              :return ::ys/response
              (if (exam-session-db/cancel-registration! db registration-id)
                (do
                  (let [registration-details      (registration-db/get-registration-data-for-clerk-mail db id registration-id)
                        lang                      (:lang registration-details)
                        exam-session-contact-info (exam-session-db/get-contact-info-by-exam-session-id db id)
                        user-portal-link          (if (:is_email_auth registration-details)
                                                    (registration/create-user-portal-link db url-helper
                                                                                          (:participant_id registration-details)
                                                                                          registration-id
                                                                                          (:exam_date registration-details))
                                                    (url-helper :yki.login.user-portal))
                        email-template-data       (assoc registration-details
                                                    :contact_info exam-session-contact-info
                                                    :user_portal_link user-portal-link)]
                    (when (= (:state registration-details) "PAID_AND_CANCELLED")
                      (log/info "Sending registration cancelled email for registration with id" registration-id "and lang" lang)
                      (registration-email/send-cancel-registration-email!
                        email-q
                        lang
                        email-template-data)))
                  (audit-log/log {:request   request
                                  :target-kv {:k audit-log/registration
                                              :v registration-id}
                                  :change    {:type audit-log/delete-op}})
                  (response {:success true}))
                (bad-request {:success false
                              :error   "Registration couldn't be cancelled"})))
            (POST "/relocate" request
              :path-params [id :- ::ys/id registration-id :- ::ys/id]
              :body [relocate-request ::ys/relocate-request]
              :return ::ys/response
              (log/info "Start relocating registration" registration-id "from session" id "to session" (:to_exam_session_id relocate-request))
              (let [to-exam-session-id (:to_exam_session_id relocate-request)
                    success?           (exam-session-db/update-registration-exam-session! db to-exam-session-id registration-id oid)]
                (if success?
                  (do
                    (let [registration-details      (registration-db/get-registration-data-for-clerk-mail db to-exam-session-id registration-id)
                          lang                      (:lang registration-details)
                          exam-session-contact-info (exam-session-db/get-contact-info-by-exam-session-id db to-exam-session-id)
                          user-portal-link          (if (:is_email_auth registration-details)
                                                      (registration/create-user-portal-link db url-helper
                                                                                            (:participant_id registration-details)
                                                                                            registration-id
                                                                                            (:exam_date registration-details))
                                                      (url-helper :yki.login.user-portal))
                          email-template-data       (assoc registration-details
                                                      :contact_info exam-session-contact-info
                                                      :user_portal_link user-portal-link)]
                      (log/info "Sending transfer confirmation email for registration with id" registration-id "and lang" lang)
                      (registration-email/send-transfer-confirmation-email!
                        email-q
                        lang
                        email-template-data))
                    (audit-log/log {:request   request
                                    :target-kv {:k audit-log/registration
                                                :v registration-id}
                                    :change    {:type audit-log/update-op
                                                :old  {:exam_session_id id}
                                                :new  {:exam_session_id (:to_exam_session_id relocate-request)}}})
                    ; Sync both the original and the new exam session
                    (exam-session-db/init-relocated-participants-sync-status! db id)
                    (exam-session-db/init-relocated-participants-sync-status! db to-exam-session-id)
                    (response {:success true}))
                  (if-let [conflicting-registration (registration-db/person-registered-to-exam-on-exam-date? db registration-id to-exam-session-id)]
                    (do (log/info "Relocate failed because of conflicting registration" {:registration-id             registration-id
                                                                                         :to-exam-session-id          to-exam-session-id
                                                                                         :conflicting-exam-session-id (:id conflicting-registration)})
                        (conflict {:success false
                                   :error   :registered}))
                    (not-found {:success false
                                :error   "Registration not found"})))))
            (POST "/resend-confirmation-email" _
              :path-params [id :- ::ys/id
                            registration-id :- ::ys/id]
              :query-params [lang :- ::ys/language-code]
              :return ::ys/response
              ; NB: Confirmation email is sent based on email address found on person table entry corresponding to person_oid found on registration table
              (if-let [registration-details (registration-db/get-completed-registration-data db id registration-id lang)]
                (if-let [payment-details (registration-db/get-completed-payment-data-for-registration db registration-id)]
                  (let [exam-session-contact-info      (exam-session-db/get-contact-info-by-exam-session-id db id)
                        exam-session-extra-information (exam-session-db/get-exam-session-location-extra-information db id lang)
                        user-portal-link               (if (:is_email_auth registration-details)
                                                         (registration/create-user-portal-link db url-helper
                                                                                               (:participant_id registration-details)
                                                                                               registration-id (:exam-date registration-details))
                                                         (url-helper :yki.login.user-portal))
                        email-template-data            (assoc registration-details
                                                         :contact_info exam-session-contact-info
                                                         :extra_information (:extra_information exam-session-extra-information)
                                                         :login_url user-portal-link)]
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
