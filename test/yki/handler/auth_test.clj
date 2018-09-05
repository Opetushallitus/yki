(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]))

  (use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

  (defn- send-request [tx request]
    (let [db (duct.database.sql/->Boundary tx)
          url-helper (ig/init-key :yki.util/url-helper {:virkailija-host "http://localhost:8080"})
          auth (ig/init-key :yki.middleware.auth/with-authentication {:db db :url-helper url-helper})
          handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth}))]
          (handler request)))

  (deftest redirect-to-login-test
    (jdbc/with-db-connection [tx embedded-db/db-spec]
      (let [request (-> (mock/request :get routing/virkailija-auth-root))
            response (send-request tx request)]
        (testing "should redirect unauthenticated user to cas login"
          (is (= (:status response) 302))))))

  (deftest handle-success-callback
    (jdbc/with-db-connection [tx embedded-db/db-spec]
      (let [request (-> (mock/request :get routing/virkailija-auth-callback))
            response (send-request tx request)]
        (testing "should validate cas ticket and set it to session"
          (is (= (:status response) 200))))))

