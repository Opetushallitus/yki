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

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [db (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/status {:db db}))]
    (handler request)))

(deftest status-ok-test
  (let [request (mock/request :get routing/status-api-root)
        response (send-request request)]
    (testing "get status endpoint should return 200 when db connection is ok"
      (is (= (:status response) 200)))))

