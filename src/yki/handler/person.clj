(ns yki.handler.person
  (:require
    [compojure.api.sweet :refer [api context GET POST DELETE]]
    [integrant.core :as ig]
    [ring.util.http-response :refer [ok not-found unauthorized]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.person-db :as person-db]
    [yki.boundary.registration-db :as registration-db]
    [yki.handler.exam-payment-new :refer [redirect-to-paytrail]]
    [yki.handler.routing :as routing]
    [yki.middleware.error-boundary :refer [with-error-boundary]]
    [yki.spec :as ys]
    [yki.registration.email :refer [send-cancel-registration-email! send-cancel-queue-email! send-transfer-confirmation-email!]]
    [yki.registration.registration :refer [create-user-portal-link]]))

(defn- has-access-to-registration? [session registration]
  (let [{:keys [auth-method identity]} session]
    (or (= "SUOMIFI" auth-method)
        (= (:id registration) (:registration-id identity)))))

(defn- with-authorized-registrations [person session]
  (update person :registrations
          (fn [registrations]
            (filter #(has-access-to-registration? session %) registrations))))

(defn- authorized-for-handler? [session]
  (let [{:keys [auth-method auth-target]} session]
    (or (= "SUOMIFI" auth-method)
        (and (= "EMAIL" auth-method)
             (= "PERSON" auth-target)))))

(defn- wrap-with-registration-authorization [session registration-id handler]
  (fn with-registration-authorization [request]
    (if (has-access-to-registration? session {:id registration-id})
      (handler request)
      (unauthorized))))

(defmethod ig/init-key :yki.handler/person [_ {:keys [db auth access-log email-q environment onr-client url-helper payment-helper]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? onr-client) (some? email-q) (some? environment) (some? url-helper) (some? payment-helper)]}
  (api
    (context routing/person-api-root []
      :coercion :spec
      :middleware [auth access-log with-error-boundary]
      (GET "/" {session :session}
        ;:return ::ys/person
        (if (authorized-for-handler? session)
          (let [oid (get-in session [:identity :oid])]
            (if oid
              (if-let [person (person-db/get-person db oid)]
                (-> person
                    (with-authorized-registrations session)
                    (ok))
                (not-found))
              (not-found "no oid in session")))
          (unauthorized)))
      (POST "/" {session :session}
        :body [person ::ys/person]
        :return ::ys/response
        (let [oid (get-in session [:identity :oid])]
          (if (person-db/upsert-person! db (assoc person :oid oid))
            ; TODO Update person details to Solki!
            (ok {:success true})
            (ok {:success false}))))
      (context (str routing/registration-uri "/:registration-id") []
        :path-params [registration-id :- ::ys/registration_id]
        (context "" {session :session}
          :middleware [#(wrap-with-registration-authorization session registration-id %)]
          (DELETE "/" {session :session}
            :query-params [lang :- ::ys/lang]
            :return ::ys/response
            (let [oid (get-in session [:identity :oid])]
              (if-let [{:keys [state exam_session_id kind]} (person-db/cancel-person-registration! db oid registration-id)]
                ; TODO Ensure Solki gets information regarding cancelled registration!
                (do
                  (when (= "PAID_AND_CANCELLED" state)
                    (let [email-data       (registration-db/get-registration-data-for-clerk-mail db exam_session_id registration-id)
                          contact-info     (exam-session-db/get-contact-info-by-exam-session-id db exam_session_id)
                          user-portal-link (if (:is_email_auth email-data)
                                             (create-user-portal-link db url-helper
                                                                      (:participant_id email-data)
                                                                      registration-id
                                                                      (:exam_date email-data))
                                             (url-helper :yki.login.user-portal))
                          template-data    (assoc email-data
                                             :contact_info contact-info
                                             :user_portal_link user-portal-link)]
                      (send-cancel-registration-email! email-q lang template-data)))
                  (when (= "QUEUE" kind)
                    (let [email-data       (registration-db/get-registration-data-for-clerk-mail db exam_session_id registration-id)
                          contact-info     (exam-session-db/get-contact-info-by-exam-session-id db exam_session_id)
                          user-portal-link (if (:is_email_auth email-data)
                                             (create-user-portal-link db url-helper
                                                                      (:participant_id email-data)
                                                                      registration-id
                                                                      (:exam_date email-data))
                                             (url-helper :yki.login.user-portal))
                          template-data    (assoc email-data
                                             :contact_info contact-info
                                             :user_portal_link user-portal-link)]
                      (send-cancel-queue-email! email-q lang template-data)))
                  (ok {:success true}))
                (ok {:success false}))))
          (GET "/confirm" {session :session}
            (let [oid                  (get-in session [:identity :oid])
                  registration-details (person-db/get-registration-to-confirm-details db oid registration-id)]
              (if (some? registration-details)
                (ok registration-details)
                (not-found))))
          (GET "/payment-redirect" {session :session}
            :query-params [lang :- ::ys/lang]
            (redirect-to-paytrail db payment-helper url-helper lang session registration-id))
          (GET "/relocate" {session :session}
            (let [; TODO What if user has no oid, ie. is authenticated with email link only?
                  oid     (get-in session [:identity :oid])
                  results (person-db/get-registration-relocate-details db oid registration-id)]
              (ok results)))
          (POST "/relocate" {session :session}
            :query-params [lang :- ::ys/lang]
            :body [relocate-request ::ys/relocate-request]
            :return ::ys/response
            (let [oid                (get-in session [:identity :oid])
                  to-exam-session-id (:to_exam_session_id relocate-request)
                  result             (person-db/relocate-registration! db oid registration-id to-exam-session-id)]
              ; TODO Update participant lists of source and target exam sessions to Solki!
              (if result
                (let [registration-details      (registration-db/get-registration-data-for-clerk-mail db to-exam-session-id registration-id)
                      exam-session-contact-info (exam-session-db/get-contact-info-by-exam-session-id db to-exam-session-id)
                      user-portal-link (if (:is_email_auth registration-details)
                                         (create-user-portal-link db url-helper
                                                                  (:participant_id registration-details)
                                                                  registration-id
                                                                  (:exam_date registration-details))
                                         (url-helper :yki.login.user-portal))
                      email-template-data       (assoc registration-details
                                                       :contact_info exam-session-contact-info
                                                       :user_portal_link user-portal-link)]
                  (send-transfer-confirmation-email! email-q lang email-template-data)
                  (ok {:success true}))
                (ok {:success false})))))))))
