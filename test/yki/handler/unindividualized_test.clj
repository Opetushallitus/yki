(ns yki.handler.unindividualized-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [yki.handler.base-test :as base]
            [clojure.string :as s]
            [jsonista.core :as j]
            [compojure.core :as core]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.auth]
            [yki.handler.routing :as routing]
            [yki.handler.registration]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest get-all-unindividualized
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (jdbc/execute! @embedded-db/conn "UPDATE registration SET created = current_timestamp WHERE person_oid = '5.4.3.2.1'")
  (with-routes!
    (fn [server]
      (merge (base/cas-mock-routes (:port server))
             {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                                        :body (slurp "test/resources/localisation.json")}}
             {"/oppijanumerorekisteri-service/henkilo/masterHenkilosByOidList" {:status 200 :content-type "application/json"
                                                                                  :body   [(j/write-value-as-string {:oidHenkilo "1.2.4.5.6" :yksiloity false})]}}))
    
      (testing "should have one registration with email type"
        (let [request (mock/request :get routing/unindividualized-uri)
              response (base/send-request-with-tx request)]
          ; (println (base/body-as-json response))
          (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
          (is (= 1 (count (first (vals (base/body-as-json response))))))
          (is (= (:status response) 200))))
      (jdbc/execute! @embedded-db/conn "UPDATE registration SET created = '2018-06-26T14:26:48.632Z' WHERE person_oid = '5.4.3.2.1'")
      (testing "should have zero registration because the singular registration from over year ago"
        (let [request (mock/request :get routing/unindividualized-uri)
              response (base/send-request-with-tx request)]
          (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
          (is (= 0 (count (first (vals (base/body-as-json response))))))
          (is (= (:status response) 200))))))