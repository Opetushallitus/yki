(ns yki.handler.status-test
  (:require [clojure.test :refer [deftest use-fixtures join-fixtures testing is]]
            [duct.database.sql]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [ring.mock.request :as mock]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.handler.status]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [db      (duct.database.sql/->Boundary @embedded-db/conn)
        handler (middleware/wrap-format (ig/init-key :yki.handler/status {:db             db
                                                                          :error-boundary (base/error-boundary)}))]
    (handler request)))

(deftest status-ok-test
  (let [request  (mock/request :get routing/status-api-root)
        response (send-request request)]
    (testing "get status endpoint should return 200 when db connection is ok"
      (is (= (:status response) 200)))))

(deftest buildversion-test
  (let [request       (mock/request :get (str routing/status-api-root "/buildversion.txt"))
        response      (send-request request)
        response-body (base/body-as-json response)]
    (testing "get buildversion.txt endpoint should return 200 and build version"
      (is (= (:status response) 200))
      (is (some? (response-body "version")))
      (is (some? (response-body "branch")))
      (is (some? (response-body "ref"))))))

