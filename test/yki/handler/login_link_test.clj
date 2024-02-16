(ns yki.handler.login-link-test
  (:require
    [clojure.test :refer [deftest is join-fixtures testing use-fixtures]]
    [clojure.java.jdbc :as jdbc]
    [duct.database.sql]
    [integrant.core :as ig]
    [jsonista.core :as j]
    [muuntaja.middleware :as middleware]
    [pgqueue.core :as pgq]
    [ring.mock.request :as mock]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]
    [yki.handler.login-link]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [port request email-q]
  (let [db      (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/login-link {:db             db
                                                                              :email-q        email-q
                                                                              :error-boundary (base/error-boundary)
                                                                              :access-log     (base/access-log)
                                                                              :url-helper     (base/create-url-helper (str "localhost:" port))}))]
    (handler request)))

(deftest login-link-create-test
  (base/insert-base-data)
  (with-routes!
    {}
    (let [email-q       (base/email-q)
          request-link! (fn [request-data]
                          (let [json-body (j/write-value-as-string request-data)
                                request   (-> (mock/request :post (str routing/login-link-api-root "?lang=fi") json-body)
                                              (mock/content-type "application/json; charset=UTF-8"))]
                            (send-request port request email-q)))]
      (testing "login link should be created with hashed code"
        (let [request-data     {:email           "test@test.com"
                                :exam_session_id 1}
              response         (request-link! request-data)
              login-link       (first (jdbc/query @embedded-db/conn "SELECT * FROM login_link"))
              code             (:code login-link)
              success-redirect (:success_redirect login-link)
              _                (base/body-as-json response)
              email-request    (pgq/take email-q)]
          (is (= (count code) 64))
          (is (= success-redirect (str "http://yki.localhost:" port "/yki/ilmoittautuminen/tutkintotilaisuus/1")))
          (is (= (:status response) 200))
          (testing "email send request should be send to job queue"
            (is (= (:subject email-request) "Ilmoittautuminen (YKI): Suomi perustaso - Omenia, 27.1.2018"))
            (is (= (:recipients email-request) ["test@test.com"])))))
      (testing "login link should not be created if exam session isn't open for registration"
        (base/execute! "UPDATE exam_date SET registration_start_date='2039-01-01' WHERE id IN (SELECT exam_date_id FROM exam_session WHERE id=1);")
        (let [request-data  {:email           "unique@email.com"
                             :exam_session_id 1}
              response      (request-link! request-data)
              email-request (pgq/take email-q)]
          (is (= (:status response) 403))
          (is (= email-request nil)))))))
