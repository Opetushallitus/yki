(ns yki.handler.registration-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [yki.handler.base-test :as base]
            [clojure.string :as s]
            [jsonista.core :as j]
            [compojure.core :as core]
            [muuntaja.middleware :as middleware]
            [pgqueue.core :as pgq]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.cas :as cas]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.auth]
            [yki.handler.routing :as routing]
            [yki.handler.registration]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- create-handlers
  [email-q port]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (base/create-url-helper (str "localhost:" port))
        auth (base/auth url-helper)
        access-log (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        auth-handler (base/auth-handler auth url-helper)
        registration-handler (middleware/wrap-format (ig/init-key :yki.handler/registration {:db db
                                                                                             :url-helper url-helper
                                                                                             :email-q email-q
                                                                                             :access-log access-log
                                                                                             :payment-config base/payment-config
                                                                                             :onr-client (base/onr-client url-helper)
                                                                                             :auth auth}))]
    (core/routes registration-handler auth-handler)))

(defn- fill-exam-session []
  (dotimes [_ 50]
    (jdbc/execute! @embedded-db/conn
                   "INSERT INTO registration(state, exam_session_id, participant_id) values ('SUBMITTED', 2, 2)")))

(def form {:first_name "Fuu"
           :last_name "Bar"
           :gender "1"
           :nationalities []
           :birthdate "1999-01-01"
           :certificate_lang "fi"
           :exam_lang "fi"
           :post_office "Helsinki"
           :zip "01000"
           :street_address "Ateläniitynpolku 29 G"
           :phone_number "04012345"
           :email "test@test.com"})

(deftest registration-create-and-update-test

  (base/insert-base-data)
  (base/insert-organizer "'1.2.3.5'")
  (base/insert-payment-config "'1.2.3.5'")
  (base/insert-languages "'1.2.3.5'")
  (base/insert-exam-session 1 "'1.2.3.5'" 50)
  (base/insert-exam-session-location "'1.2.3.5'" "fi")
  (base/insert-exam-session-location "'1.2.3.5'" "sv")
  (base/insert-exam-session-location "'1.2.3.5'" "en")
  (base/insert-login-link base/code-ok "2038-01-01")
  (jdbc/execute! @embedded-db/conn "INSERT INTO exam_session_queue (email, lang, exam_session_id) VALUES ('test@test.com', 'sv', 1)")
  (with-routes!
    (fn [server]
      (merge (base/cas-mock-routes (:port server))
             {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                                        :body (slurp "test/resources/localisation.json")}}
             {"/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto" {:status 200 :content-type "application/json"
                                                                                  :body   (j/write-value-as-string {:oidHenkilo "1.2.4.5.6"})}}))
    (let [email-q (base/email-q)
          handlers (create-handlers email-q (:port server))
          session (base/login-with-login-link (peridot/session handlers))
          init-response (-> session
                            (peridot/request (str routing/registration-api-root "/init")
                                             :body (j/write-value-as-string {:exam_session_id 1})
                                             :content-type "application/json"
                                             :request-method :post))
          init-response-body (base/body-as-json (:response init-response))
          id (init-response-body "registration_id")
          create-twice-response (-> session
                                    (peridot/request (str routing/registration-api-root "/init")
                                                     :body (j/write-value-as-string {:exam_session_id 1})
                                                     :content-type "application/json"
                                                     :request-method :post))
          registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))
          update-response (-> session
                              (peridot/request (str routing/registration-api-root "/" id "/submit" "?lang=fi")
                                               :body (j/write-value-as-string form)
                                               :content-type "application/json"
                                               :request-method :post))
          payment (base/select-one (str "SELECT * FROM payment WHERE registration_id = " id))
          exam-session (base/select-one "SELECT * FROM exam_date WHERE id = 1")
          payment-link (base/select-one (str "SELECT * FROM login_link WHERE registration_id = " id))
          submitted-registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))
          email-request (pgq/take email-q)]
      (testing "post init endpoint should create registration with status STARTED"
        (is (= (get-in init-response [:response :status]) 200))
        (is (= (init-response-body (j/read-value (slurp "test/resources/init_registration_response.json")))))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "second post before submitting should return init data"
        (let [create-twice-response-body (base/body-as-json (:response create-twice-response))]
          (is (= (get-in create-twice-response [:response :status]) 200))
          (is (= (init-response-body (j/read-value (slurp "test/resources/init_registration_response.json")))))))

      (testing "post submit endpoint should create payment"
        (is (= (get-in update-response [:response :status]) 200))
        (is (= (:id payment) id)))
      (testing "and send email with payment link"
        (is (= (:subject email-request) "Maksulinkki: suomi perustaso - Omenia, 27.1.2018"))
        (is (s/includes? (:body email-request) "100,00 €"))
        (is (= (:type payment-link) "PAYMENT"))
        (is (= (:success_redirect payment-link) (str "http://yki.localhost:" port "/yki/maksu/" id)))
        (is (= (:order_number payment) "YKI6000000001")))
      (testing "and set registration status to SUBMITTED"
        (is (= (:state submitted-registration) "SUBMITTED"))
        (is (= (instance? clojure.lang.PersistentHashMap (:form submitted-registration))))
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
        (fill-exam-session)
        (let [session-full-response (-> session
                                        (peridot/request (str routing/registration-api-root "/init")
                                                         :body (j/write-value-as-string {:exam_session_id 2})
                                                         :content-type "application/json"
                                                         :request-method :post))]
          (is (= (get-in session-full-response [:response :status]) 409))
          (is (= (get-in (base/body-as-json (:response session-full-response)) ["error" "full"]) true)))))))
