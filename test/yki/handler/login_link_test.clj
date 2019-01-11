(ns yki.handler.login-link-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [stub-http.core :refer :all]
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

(defn- send-request [port request email-q]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/login-link {:db db
                                                                              :email-q email-q
                                                                              :access-log (base/access-log)
                                                                              :url-helper (base/create-url-helper (str "localhost:" port))}))]
    (handler request)))

(deftest login-link-create-test
  (base/insert-login-link-prereqs)
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [email-q (base/email-q)
          json-body (j/write-value-as-string {:email "test@test.com"
                                              :exam_session_id 1})
          request (-> (mock/request :post (str routing/login-link-api-root "?lang=fi") json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (send-request port request email-q)
          login-link (first (jdbc/query @embedded-db/conn "SELECT * FROM login_link"))
          code (:code login-link)
          success-redirect (:success_redirect login-link)
          response-body (base/body-as-json response)
          email-request (pgq/take email-q)]
      (testing "login link should be created with hashed code"
        (is (= (count code) 64))
        (is (= success-redirect (str "http://yki.localhost:" port "/yki/ilmoittautuminen?action=login&id=1")))
        (is (= (:status response) 200))
        (testing "email send request should be send to job queue"
          (is (= (:subject email-request) "Ilmoittautuminen: suomi perustaso - Omenia, 27.1.2018"))
          (is (= (:recipients email-request) ["test@test.com"])))))))

