(ns yki.handler.status-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.status]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(defn- send-request [tx request]
  (let [db (duct.database.sql/->Boundary tx)
        handler (middleware/wrap-format (ig/init-key :yki.handler/status {:db db}))]
    (handler request)))

(deftest status-ok-test
  (jdbc/with-db-connection [tx embedded-db/db-spec]
    (let [request (mock/request :get routing/status-api-root)
          response (send-request tx request)]
      (testing "get status endpoint should return 200 when db connection is ok"
        (is (= (:status response) 200))))))

