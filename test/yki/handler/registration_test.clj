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
            [clojure.java.jdbc :as jdbc]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.cas :as cas]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.registration]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- create-handlers []
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :domain "localhost"
                                                           :path "/yki"}}})
        url-helper (base/create-url-helper "localhost:8080")
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))
        registration-handler (middleware/wrap-format (ig/init-key :yki.handler/registration {:db db
                                                                                             :url-helper url-helper
                                                                                             :payment-config base/payment-config
                                                                                             :auth auth}))]
    (core/routes registration-handler auth-handler)))

(deftest registration-create-and-update-test
  (base/insert-login-link-prereqs)
  (base/insert-login-link base/code-ok "2038-01-01")
  (let [handlers (create-handlers)
        session (base/login-with-login-link (peridot/session handlers))
        create-response (-> session
                            (peridot/request routing/registration-api-root
                                             :body (j/write-value-as-string {:exam_session_id 1})
                                             :content-type "application/json"
                                             :request-method :post))
        id ((base/body-as-json (:response create-response)) "id")
        registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))
        update-response (-> session
                            (peridot/request (str routing/registration-api-root "/" id)
                                             :body (j/write-value-as-string {:exam_session_id 1})
                                             :content-type "application/json"
                                             :request-method :put))
        payment (base/select-one (str "SELECT * FROM payment WHERE registration_id = " id))
        updated-registration (base/select-one (str "SELECT * FROM registration WHERE id = " id))]
    (testing "post endpoint should create registration with status STARTED"
      (is (= (get-in create-response [:response :status]) 200))
      (is (= (:state registration) "STARTED"))
      (is (some? (:started_at registration))))
    (testing "put endpoint should create payment and set registration status to SUBMITTED"
      (is (= (get-in update-response [:response :status]) 200))
      (is (= (:id payment) id))
      (is (= (:order_number payment) "YKI1"))
      (is (= (:state updated-registration) "SUBMITTED"))
      (is (some? (:started_at registration))))))
