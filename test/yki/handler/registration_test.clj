(ns yki.handler.registration-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [jsonista.core :as j]
            [peridot.core :as peridot]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.registration]
            [yki.handler.registration-commons :refer [common-bindings
                                                      common-route-specs
                                                      fill-exam-session
                                                      insert-common-base-data
                                                      registration-form-data
                                                      registration-success-redirect]]
            [yki.handler.routing :as routing]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- insert-initial-data! []
  (let [organizer-oid "1.2.3.5"]
    (insert-common-base-data organizer-oid)
    (base/insert-exam-session 1 organizer-oid 50)
    (base/insert-exam-session-location organizer-oid "fi")
    (base/insert-exam-session-location organizer-oid "sv")
    (base/insert-exam-session-location organizer-oid "en")
    (base/insert-login-link base/code-ok "2038-01-01")
    (jdbc/execute! @embedded-db/conn "INSERT INTO exam_session_queue (email, lang, exam_session_id) VALUES ('test@test.com', 'sv', 1)")))

(deftest registration-create-and-update-with-new-payments-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [{session               :session
           init-response         :init-response
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
        (is (= init-response-body (j/read-value (slurp "test/resources/init_registration_response.json"))))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "second post before submitting should return init data"
        (let [create-twice-response-body (base/body-as-json (:response create-twice-response))]
          (is (= (get-in create-twice-response [:response :status]) 200))
          (is (= create-twice-response-body (j/read-value (slurp "test/resources/init_registration_response.json"))))))

      (testing "post submit endpoint should return status 200, but payment should not yet be created"
        (is (= (get-in (submit-form! registration-form-data) [:response :status]) 200))
        (is (nil? (get-payment))))

      (testing "and send email with payment link"
        (let [email-request (get-email-request)
              payment-link  (get-payment-link)]
          (is (= (:subject email-request) "Maksulinkki (YKI): Suomi perustaso - Omenia, 27.1.2018"))
          (is (s/includes? (:body email-request) "135,00 €"))
          (is (s/includes? (:body email-request) "Omenia, Upseerinkatu 11, 00240 ESPOO"))
          (is (= (:type payment-link) "PAYMENT"))
          (is (= (:success_redirect payment-link) (registration-success-redirect registration-id port)))))

      (let [registration (get-registration)]
        (testing "and set registration status to SUBMITTED"
          (is (= (:state registration) "SUBMITTED"))
          (is (map? (:form registration)))
          (is (some? (:started_at registration))))

        (testing "sanitize registration input"
          (is (= (get-in registration [:form :post_office]) "Helsinki_"))))

      (testing "and delete item from exam session queue"
        (is (= {:count 0}
               (base/select-one "SELECT COUNT(1) FROM exam_session_queue"))))

      (testing "second post to same session after submit should return conflict with proper error"
        (let [create-twice-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 1})
                                                         :content-type "application/json"
                                                         :request-method :post))]

          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "registered"]) true))
          (is (= (get-in create-twice-response [:response :status]) 409))))

      (testing "second post to another session should return conflict with proper error"
        (let [create-twice-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]

          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "registered"]) true))
          (is (= (get-in create-twice-response [:response :status]) 409))))

      (testing "when session is full should return conflict with proper error"
        (fill-exam-session 50, "ADMISSION")
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
          (is (= ssn (get-in registration [:form :ssn])))
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
