(ns yki.handler.registration-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [yki.handler.base-test :as base]
            [jsonista.core :as j]
            [compojure.core :as core]
            [muuntaja.middleware :as middleware]
            [pgqueue.core :as pgq]
            [clojure.java.jdbc :as jdbc]
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
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :domain "localhost"
                                                           :path "/yki"}}})
        url-helper (base/create-url-helper (str "localhost:" port))
        cas-client (ig/init-key  :yki.boundary.cas/cas-client {:url-helper url-helper
                                                               :cas-creds {:username "username"
                                                                           :password "password"}})
        onr-client (ig/init-key :yki.boundary.onr/onr-client {:url-helper url-helper
                                                              :cas-client cas-client})
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))
        registration-handler (middleware/wrap-format (ig/init-key :yki.handler/registration {:db db
                                                                                             :url-helper url-helper
                                                                                             :email-q email-q
                                                                                             :payment-config base/payment-config
                                                                                             :onr-client onr-client
                                                                                             :auth auth}))]
    (core/routes registration-handler auth-handler)))

(defn- fill-exam-session []
  (dotimes [_ 4]
    (jdbc/execute! @embedded-db/conn
                   "INSERT INTO registration(state, exam_session_id, participant_id) values ('SUBMITTED', 1, 1)")))

(def form {:first_name "Fuu"
           :last_name "Bar"
           :gender "M"
           :nationalities []
           :birth_date "1999-01-01"
           :certificate_lang "fi"
           :exam_lang "fi"
           :post_office "Helsinki"
           :zip "01000"
           :street_address "Ateläniitynpolku 29 G"
           :phone_number "04012345"
           :email "test@test.com"})

(deftest registration-create-and-update-test
  (base/insert-login-link-prereqs)
  (base/insert-login-link base/code-ok "2038-01-01")
  (with-routes!
    (fn [server]
      (merge (base/cas-mock-routes (:port server))
             {"/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto" {:status 200 :content-type "application/json"
                                                                                  :body   (j/write-value-as-string {:henkiloOid "1.2.4.5.6"})}}))
    (let [email-q (ig/init-key :yki.job.job-queue/email-q {:db-config {:db embedded-db/db-spec}})
          handlers (create-handlers email-q (:port server))
          session (base/login-with-login-link (peridot/session handlers))
          create-response (-> session
                              (peridot/request routing/registration-api-root
                                               :body (j/write-value-as-string {:exam_session_id 1})
                                               :content-type "application/json"
                                               :request-method :post))
          id ((base/body-as-json (:response create-response)) "id")
          registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))
          update-response (-> session
                              (peridot/request (str routing/registration-api-root "/" id "?lang=fi")
                                               :body (j/write-value-as-string form)
                                               :content-type "application/json"
                                               :request-method :put))
          payment (base/select-one (str "SELECT * FROM payment WHERE registration_id = " id))
          payment-link (base/select-one (str "SELECT * FROM login_link WHERE registration_id = " id))
          submitted-registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))
          email-request (pgq/take email-q)]

      (testing "post endpoint should create registration with status STARTED"
        (is (= (get-in create-response [:response :status]) 200))
        (is (= (:state registration) "STARTED"))
        (is (some? (:started_at registration))))

      (testing "put endpoint should create payment, send email with payment link and set registration status to SUBMITTED"
        (is (= (get-in update-response [:response :status]) 200))
        (is (= (:id payment) id))
        (is (= (:subject email-request) "Maksulinkki"))
        (is (= (:type payment-link) "PAYMENT"))
        (is (= (:order_number payment) "YKI1"))
        (is (= (:state submitted-registration) "SUBMITTED"))
        (is (= (instance? clojure.lang.PersistentHashMap (:form submitted-registration))))
        (is (some? (:started_at submitted-registration))))

      (testing "second post with same data should return conflict with proper error"
        (let [create-twice-response (-> session
                                        (peridot/request routing/registration-api-root
                                                         :body (j/write-value-as-string {:exam_session_id 1})
                                                         :content-type "application/json"
                                                         :request-method :post))]

          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "registered"]) true))
          (is (= (get-in create-twice-response [:response :status]) 409))))

      (testing "when session is full should return conflict with proper error"
        (fill-exam-session)
        (let [create-twice-response (-> session
                                        (peridot/request routing/registration-api-root
                                                         :body (j/write-value-as-string {:exam_session_id 1})
                                                         :content-type "application/json"
                                                         :request-method :post))]
          (is (= (get-in create-twice-response [:response :status]) 409))
          (is (= (get-in (base/body-as-json (:response create-twice-response)) ["error" "full"]) true)))))))
