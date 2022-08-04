(ns yki.handler.post-registration-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [jsonista.core :as j]
            [peridot.core :as peridot]
            [stub-http.core :refer [with-routes!]]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.registration]
            [yki.handler.registration-commons :refer [common-bindings
                                                      common-route-specs
                                                      fill-exam-session
                                                      insert-common-base-data
                                                      registration-success-redirect]]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- insert-initial-data! []
  (let [organizer-oid "1.2.3.5"]
    (insert-common-base-data organizer-oid)
    ; both exam sessions are expected to share post admission dates for the relevant checks to work
    (jdbc/execute! @embedded-db/conn "UPDATE exam_date SET registration_end_date = '2018-12-01', post_admission_start_date = '2018-12-07', post_admission_end_date = '2039-12-31', post_admission_enabled = true")
    (jdbc/execute! @embedded-db/conn "UPDATE exam_session SET post_admission_quota = 20, post_admission_active = true WHERE id = 1")
    (base/insert-exam-session-with-post-admission 1 organizer-oid 50 20)
    (base/insert-exam-session-location organizer-oid "fi")
    (base/insert-exam-session-location organizer-oid "sv")
    (base/insert-exam-session-location organizer-oid "en")
    (base/insert-login-link base/code-ok "2038-01-01")
    (jdbc/execute! @embedded-db/conn "INSERT INTO exam_session_queue (email, lang, exam_session_id) VALUES ('test@test.com', 'sv', 1)")))

(deftest registration-post-admission-create-and-update-with-legacy-payments-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [use-new-payments-api? false
          {session                :session
           init-response          :init-response
           init-response-body     :init-response-body
           registration           :registration
           registration-id        :registration-id
           create-twice-response  :create-twice-response
           submit-response        :submit-response
           payment                :payment
           payment-link           :payment-link
           submitted-registration :submitted-registration
           email-request          :email-request} (common-bindings server use-new-payments-api?)]
      (testing "post init endpoint should create registration with status STARTED"
        (is (= (get-in init-response [:response :status]) 200))
        (is (= init-response-body (j/read-value (slurp "test/resources/init_post_registration_response.json"))))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "second post before submitting should return init data"
        (let [create-twice-response-body (base/body-as-json (:response create-twice-response))]
          (is (= (get-in create-twice-response [:response :status]) 200))
          (is (= create-twice-response-body (j/read-value (slurp "test/resources/init_post_registration_response.json"))))))

      (testing "post submit endpoint should create payment"
        (is (= (get-in submit-response [:response :status]) 200))
        (is (= (:id payment) registration-id)))

      (testing "and send email with payment link"
        (is (= (:subject email-request) "Maksulinkki: suomi perustaso - Omenia, 27.1.2018"))
        (is (s/includes? (:body email-request) "100,00 €"))
        (is (s/includes? (:body email-request) "Omenia, Upseerinkatu 11, 00240 Espoo"))
        (is (= (:type payment-link) "PAYMENT"))
        (is (= (:success_redirect payment-link) (registration-success-redirect registration-id port use-new-payments-api?)))
        (is (= (:order_number payment) "YKI6000000001")))

      (testing "and set registration status to SUBMITTED"
        (is (= (:state submitted-registration) "SUBMITTED"))
        (is (map? (:form submitted-registration)))
        (is (some? (:started_at submitted-registration))))

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
        (fill-exam-session 20 "POST_ADMISSION")
        (let [session-full-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]
          (is (= (get-in session-full-response [:response :status]) 409))
          (is (= (get-in (base/body-as-json (:response session-full-response)) ["error" "full"]) true)))))))

(deftest registration-post-admission-create-and-update-with-new-payments-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [use-new-payments-api? true
          {session                :session
           init-response          :init-response
           init-response-body     :init-response-body
           registration           :registration
           registration-id        :registration-id
           create-twice-response  :create-twice-response
           submit-response        :submit-response
           payment                :payment
           payment-link           :payment-link
           submitted-registration :submitted-registration
           email-request          :email-request} (common-bindings server use-new-payments-api?)]
      (testing "post init endpoint should create registration with status STARTED"
        (is (= (get-in init-response [:response :status]) 200))
        (is (= init-response-body (j/read-value (slurp "test/resources/init_post_registration_response.json"))))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "second post before submitting should return init data"
        (let [create-twice-response-body (base/body-as-json (:response create-twice-response))]
          (is (= (get-in create-twice-response [:response :status]) 200))
          (is (= create-twice-response-body (j/read-value (slurp "test/resources/init_post_registration_response.json"))))))

      (testing "post submit endpoint should return status 200, but payment should not yet be created"
        (is (= (get-in submit-response [:response :status]) 200))
        (is (nil? payment)))

      (testing "and send email with payment link"
        (is (= (:subject email-request) "Maksulinkki: suomi perustaso - Omenia, 27.1.2018"))
        (is (s/includes? (:body email-request) "100,00 €"))
        (is (s/includes? (:body email-request) "Omenia, Upseerinkatu 11, 00240 Espoo"))
        (is (= (:type payment-link) "PAYMENT"))
        (is (= (:success_redirect payment-link) (registration-success-redirect registration-id port use-new-payments-api?))))

      (testing "and set registration status to SUBMITTED"
        (is (= (:state submitted-registration) "SUBMITTED"))
        (is (map? (:form submitted-registration)))
        (is (some? (:started_at submitted-registration))))

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
        (fill-exam-session 20 "POST_ADMISSION")
        (let [session-full-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]
          (is (= (get-in session-full-response [:response :status]) 409))
          (is (= (get-in (base/body-as-json (:response session-full-response)) ["error" "full"]) true)))))))
