(ns yki.handler.login-link-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [duct.database.sql]
            [muuntaja.middleware :as middleware]
            [yki.handler.base-test :as base]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.login-link]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/login-link {:db db}))]
    (handler request)))

(deftest login-link-create-test
  (base/insert-login-link-prereqs)
  (let [json-body (j/write-value-as-string {:email "login-link-create-test@test.com"
                                            :exam_session_id 1
                                            :expired_link_redirect "http://localhost:8080/expired"
                                            :success_redirect "http://localhost:8080/success"
                                            :expires_at "2040-01-01"})
        request (-> (mock/request :post routing/login-link-api-root json-body)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (send-request request)
        response-body (base/body-as-json response)]
    (testing "login link should be created"
      (is (= '({:count 1})
             (jdbc/query @embedded-db/conn "SELECT COUNT(1) FROM login_link")))
      (is (= (:status response) 200)))))

