(ns yki.handler.registration-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.string :as str]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [peridot.core :as peridot]
            [pgqueue.core :as pgq]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.registration]
            [yki.handler.registration-commons :refer [common-bindings
                                                      common-route-specs
                                                      create-handlers
                                                      fill-exam-session
                                                      insert-common-base-data
                                                      registration-form-data
                                                      registration-success-redirect]]
            [yki.handler.routing :as routing]
            [yki.util.common :refer [date-from-now previous-day format-date-to-finnish-format]]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def organizer-oid "1.2.3.5")

(defn- insert-initial-data!
  ([]
   (insert-initial-data! 50))
  ([max-participants]
   (insert-common-base-data organizer-oid)
   (base/insert-exam-session 1 organizer-oid max-participants)
   (base/insert-exam-session-location organizer-oid "fi")
   (base/insert-exam-session-location organizer-oid "sv")
   (base/insert-exam-session-location organizer-oid "en")
   (base/insert-login-link {:code       base/code-ok
                            :expires-at "2038-01-01"})))

(deftest registration-create-and-update-with-new-payments-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [{session               :session
           init-response         :init-response
           identify-response     :identify-response
           init-response-body    :init-response-body
           registration          :registration
           registration-id       :registration-id
           create-twice-response :create-twice-response
           submit-form!          :submit-form!
           get-payment           :get-payment
           get-payment-link      :get-payment-link
           get-registration      :get-registration
           get-email-request     :get-email-request} (common-bindings server)]
      (testing "post init endpoint should create registration with status STARTED"
        (is (= (get-in init-response [:response :status]) 200))
        (is (= (dissoc init-response-body "expires_in") (j/read-value (slurp "test/resources/init_registration_response.json"))))
        (is (number? (init-response-body "expires_in")))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "second post before submitting should return init data"
        (let [create-twice-response-body (base/body-as-json (:response create-twice-response))]
          (is (= (get-in create-twice-response [:response :status]) 200))
          (is (= (dissoc create-twice-response-body "expires_in") (j/read-value (slurp "test/resources/init_registration_response.json"))))
          (is (number? (create-twice-response-body "expires_in")))))

      (testing "post identify endpoint should identify registration"
        (is (= (get-in identify-response [:response :status]) 200))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "post submit endpoint should return status 200, but payment should not yet be created"
        (is (= (get-in (submit-form! registration-form-data) [:response :status]) 200))
        (is (nil? (get-payment))))

      (testing "and send email with payment link"
        (let [email-request       (get-email-request)
              payment-link        (get-payment-link)
              payment-link-expiry (date-from-now (inc 3))
              last-payment-date   (previous-day payment-link-expiry)]
          (is (= (:subject email-request) "Maksulinkki (YKI): Suomi perustaso - Omenia, 27.1.2018"))
          (is (str/includes? (:body email-request) "135,00 €"))
          (is (str/includes? (:body email-request) "Omenia, Upseerinkatu 11, 00240 ESPOO"))
          (is (= (:type payment-link) "PAYMENT"))
          (is (= (.toDate (:expires_at payment-link))
                 (.toDate payment-link-expiry)))
          (is (str/includes? (:body email-request) (str "Maksa tutkintomaksu viimeistään " (format-date-to-finnish-format last-payment-date))))
          (is (= (:success_redirect payment-link) (registration-success-redirect registration-id port)))))

      (let [registration (get-registration)]
        (testing "and set registration status to SUBMITTED"
          (is (= (:state registration) "SUBMITTED"))
          (is (map? (:form registration)))
          (is (some? (:started_at registration))))

        (testing "sanitize registration input"
          (is (= (get-in registration [:form :post_office]) "Helsinki_"))))

      (testing "second post to same session after submit should return conflict with proper error"
        (let [create-twice-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 1})
                                                         :content-type "application/json"
                                                         :request-method :post))]

          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "other-exam-session-registration"]) {"id" 1, "state" "SUBMITTED"}))
          (is (= (get-in create-twice-response [:response :status]) 409))))

      (testing "second post to another session should return conflict with proper error"
        (let [create-twice-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]

          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "other-exam-session-registration"]) {"id" 1, "state" "SUBMITTED"}))
          (is (= (get-in create-twice-response [:response :status]) 409))))

      (testing "when session is full should return conflict with proper error"
        (fill-exam-session 50 "ADMISSION")
        (let [session-full-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]
          (is (= (get-in session-full-response [:response :status]) 409))
          (is (= (get-in (base/body-as-json (:response session-full-response)) ["error" "full"]) true)))))))

(deftest registration-create-with-ssn
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [{submit-form!     :submit-form!
           get-registration :get-registration} (common-bindings server)
          ssn                "010170-999R"
          inferred-birthdate "1970-01-01"]
      (testing "submitting form with SSN but no birthdate should result in birthdate inferred from SSN"
        (submit-form! (-> registration-form-data
                          (dissoc :birthdate)
                          (assoc :ssn ssn)))
        (let [registration (get-registration)]
          (is (nil? (get-in registration [:form :ssn])))
          (is (= inferred-birthdate (get-in registration [:form :birthdate]))))))))

(deftest registration-cancellation-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [{registration         :registration
           get-registration     :get-registration
           cancel-registration! :cancel-registration!} (common-bindings server)
          reset-to-state! #(base/update-registration-state! (:id registration) %)]
      (testing "participant can cancel their own registration if in STARTED state"
        (is (= 200 (-> (cancel-registration!) :response :status)))
        (is (= "CANCELLED" (:state (get-registration))))
        (is (= 400 (-> (cancel-registration!) :response :status)))
        (is (= "CANCELLED" (:state (get-registration))))
        (reset-to-state! "STARTED"))
      (testing "cannot cancel registration if it belongs to another participant"
        (base/execute! (str "UPDATE registration SET participant_id=" (inc (:participant_id registration)) " WHERE id=" (:id registration)))
        (is (= 400 (-> (cancel-registration!) :response :status)))
        (is (= "STARTED" (:state (get-registration))))
        (base/execute! (str "UPDATE registration SET participant_id=" (:participant_id registration) " WHERE id=" (:id registration)))
        (is (= 200 (-> (cancel-registration!) :response :status)))
        (is (= "CANCELLED" (:state (get-registration))))))))

(deftest registration-to-queue
  (let [max-participants 1]
    (insert-initial-data! max-participants)
    (with-routes!
      common-route-specs
      (let [handlers              (create-handlers (base/email-q) (:port server))
            session               (-> (peridot/session handlers)
                                      (base/login-with-login-link))
            json-mapper           (j/object-mapper {:decode-key-fn true})
            init-registration!    (fn [session exam-session-id to-queue?]
                                    (-> session
                                        (peridot/request
                                          (str routing/registration-api-root "/init")
                                          :body (j/write-value-as-string {:exam_session_id exam-session-id
                                                                          :to_queue        to-queue?})
                                          :content-type "application/json"
                                          :request-method :post)))
            exam-session-id       (-> (str "SELECT id FROM exam_session WHERE organizer_id=(SELECT id FROM organizer WHERE oid='" organizer-oid "');")
                                      (base/select-one)
                                      (:id))
            participant-2-email   "anothertest@user.com"
            participant-2-id      (:id (base/select-one (str "SELECT id FROM participant WHERE email='" participant-2-email "';")))
            participant-2-code    (str/replace base/code-ok \8 \0)
            _                     (base/insert-login-link {:code         participant-2-code
                                                           :expires-at   "2038-01-01"
                                                           :participant  participant-2-id
                                                           :exam-session exam-session-id})
            participant-2-session (-> (peridot/session handlers)
                                      (base/login-with-login-link participant-2-code))]
        (testing "enrolling to queue succeeds when exam is full"
          ; Initialise registration with kind == "ADMISSION"
          (let [{:keys [status body]} (-> (init-registration! session exam-session-id false) :response)
                queue-size (-> (j/read-value body json-mapper)
                               (:exam_session)
                               (:queue))]
            (is (= 200 status))
            ; Queue should be empty at this point
            (is (= 0 queue-size)))
          ; Exam session is now full -> enrolling to queue (with another participant!) should succeed
          (let [{:keys [status body]} (-> (init-registration! participant-2-session exam-session-id true) :response)
                queue-size (-> (j/read-value body json-mapper)
                               (:exam_session)
                               (:queue))]
            (is (= 200 status))
            ; Queue should now have one entry
            (is (= 1 queue-size))))
        ; NB! The following test case should the last within a deftest block, as the request triggers an exception
        ; during the database transaction. This will lead to the transaction getting terminated and all DB changes,
        ; *including* the ones for test setup, are rolled back!
        (testing "enrolling to queue fails if exam is not yet full and has no existing queue"
          (let [new-participant-email   "fresh@test.invalid"
                _                       (base/execute! (str "INSERT INTO participant (external_user_id, email) VALUES ('" new-participant-email "','" new-participant-email "');"))
                new-participant-id      (:id (base/select-one (str "SELECT id FROM participant WHERE external_user_id='" new-participant-email "';")))
                new-participant-code    (str/replace base/code-ok \8 \3)
                _                       (base/insert-login-link {:code         new-participant-code
                                                                 :expires-at   "2038-01-01"
                                                                 :participant  new-participant-id
                                                                 :exam-session 1})
                new-participant-session (-> (peridot/session handlers)
                                            (base/login-with-login-link new-participant-code))
                ; Exam session with id 1 should not be full currently
                {:keys [status body]} (-> (init-registration! new-participant-session 1 true) :response)
                response-body           (j/read-value body json-mapper)]
            (is (= 409 status))
            (is (= {:error {:registration_kind true}} response-body))))))))

(deftest free-registration-test
  (insert-initial-data!)
  (base/execute! "INSERT INTO participant (external_user_id) VALUES ('1.2.3.5.001')")
  (with-routes!
    common-route-specs
    (let [email-q                   (base/email-q)
          oid                       "1.2.3.5.001"
          fake-session              {:identity    {:oid              oid
                                                   :first_name       "Etu"
                                                   :last_name        "Suku"
                                                   :external-user-id oid
                                                   :registration-id  1}
                                     :auth-method "SUOMIFI"}
          auth                      (ig/init-key :yki.middleware.no-auth/with-fake-session fake-session)
          url-helper                (base/create-url-helper (str "localhost:" port))
          handlers                  (create-handlers email-q (:port server) auth)
          session                   (peridot/session handlers)
          email-q                   (base/email-q)
          get-registration          (fn [registration-id] (base/select-one (str "SELECT * FROM registration WHERE id = " registration-id)))
          init-registration!        (fn [exam-session-id]
                                      (-> session
                                          (peridot/request (str routing/registration-api-root "/init")
                                                           :body (j/write-value-as-string {:exam_session_id exam-session-id})
                                                           :content-type "application/json"
                                                           :request-method :post)))
          insert-free-registration! (fn [registration-id]
                                      (jdbc/execute!
                                        @embedded-db/conn
                                        (str "INSERT INTO free_registration (source, type, matriculation_exam, higher_education_concluded, higher_education_enrolled, eb, dia, other, registration_id, is_foreign) VALUES ('KOSKI', 'HigherEducationConcluded', true, false, false, false, false, false, " registration-id ", false)")
                                        {:return-keys true}))
          submit-registration!      (fn [registration-id form]
                                      (-> session
                                          (peridot/request (str routing/registration-api-root "/" registration-id "/submit" "?lang=fi")
                                                           :body (j/write-value-as-string form)
                                                           :content-type "application/json"
                                                           :request-method :post)))
          cancel-registration!      (fn [registration-id]
                                      (base/execute! (str "UPDATE registration SET state='CANCELLED' WHERE id=" registration-id)))]
      (testing "submitting registration form with matching free registration id"
        (testing "should immediately enroll user to exam session if available registration kind is 'ADMISSION'"
          (let [init-response        (init-registration! 1)
                init-response-body   (base/body-as-json (:response init-response))
                registration-id      (init-response-body "registration_id")
                free-registration-id (:free_registration_id (insert-free-registration! registration-id))]
            (is (= "STARTED" (:state (get-registration registration-id))))
            (submit-registration! registration-id (assoc registration-form-data :free_registration_id free-registration-id))
            (is (= "COMPLETED" (:state (get-registration registration-id))))
            (let [email (pgq/take email-q)]
              (is (str/includes? (:subject email) "Ilmoittautuminen YKI-testiin onnistui"))
              (is (str/includes? (:body email) "Sinun ei tarvitse maksaa tutkintomaksua YKI-testiin.")))
            (cancel-registration! registration-id)))
        (testing "should enroll user to queue if available registration kind is 'QUEUE'"
          (let [init-response        (init-registration! 1)
                init-response-body   (base/body-as-json (:response init-response))
                registration-id      (init-response-body "registration_id")
                free-registration-id (:free_registration_id (insert-free-registration! registration-id))]
            (is (= "STARTED" (:state (get-registration registration-id))))
            (base/execute! (str "UPDATE registration SET kind='QUEUE' WHERE id=" registration-id))
            (submit-registration! registration-id (assoc registration-form-data :free_registration_id free-registration-id))
            (is (= "SUBMITTED" (:state (get-registration registration-id))))
            (let [email (pgq/take email-q)]
              (is (str/includes? (:subject email) "Ilmoittautuminen jonoon (YKI)"))
              (is (str/includes? (:body email) "Olet ilmoittautunut jonoon")))
            (testing "and lifting registration from queue should immediately enroll user to session"
              (let [registration-state-handler (ig/init-key :yki.job.scheduled-tasks/registration-queue-handler
                                                            {:db             (base/db)
                                                             :url-helper     url-helper
                                                             :payment-helper {}
                                                             :email-q        email-q})
                    _                          (registration-state-handler)]
                (is (= "COMPLETED" (:state (get-registration registration-id))))
                (let [email (pgq/take email-q)]
                  (is (str/includes? (:subject email) "Olet saanut paikan YKI-testiin jonosta"))
                  (is (str/includes? (:body email) "Sinun ei tarvitse maksaa tutkintomaksua YKI-testiin."))))
              (cancel-registration! registration-id)))))
      (testing "submitting registration form with unmatching free registration id should yield error"
        (let [init-response        (init-registration! 1)
              init-response-body   (base/body-as-json (:response init-response))
              registration-id      (init-response-body "registration_id")
              free-registration-id (:free_registration_id (insert-free-registration! registration-id))]
          (is (= "STARTED" (:state (get-registration registration-id))))
          (let [status (-> (submit-registration! registration-id (assoc registration-form-data :free_registration_id (+ 999 free-registration-id)))
                           (:response)
                           (:status))]
            (is (= 500 status)))
          (is (= "STARTED" (:state (get-registration registration-id)))))))))
