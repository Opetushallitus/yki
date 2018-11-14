(ns yki.handler.login-link-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [duct.database.sql]
            [pgqueue.core :as pgq]
            [muuntaja.middleware :as middleware]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.login-link]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request email-q]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/login-link {:db db :email-q email-q :url-helper (base/create-url-helper "localhost")}))]
    (handler request)))

(deftest login-link-create-test
  (base/insert-login-link-prereqs)
  (let [email-q (ig/init-key :yki.job.job-queue/email-q {:db-config {:db embedded-db/db-spec}})
        json-body (j/write-value-as-string {:email "test@test.com"
                                            :exam_session_id 1
                                            :expired_link_redirect "http://localhost:8080/expired"
                                            :success_redirect "http://localhost:8080/success"})
        request (-> (mock/request :post (str routing/login-link-api-root "?lang=fi") json-body)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (send-request request email-q)
        code (:code (first (jdbc/query @embedded-db/conn "SELECT code FROM login_link")))
        response-body (base/body-as-json response)
        email-request (pgq/take email-q)]
    (testing "login link should be created with hashed code"
      (is (= (count code) 64))
      (is (= (:status response) 200))
      (testing "email send request should be send to job queue"
        (is (= (:subject email-request) "Ilmoittautuminen"))
        (is (= (:recipients email-request) ["test@test.com"]))))))

