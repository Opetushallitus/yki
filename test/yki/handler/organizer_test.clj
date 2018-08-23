(ns yki.handler.organizer-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [cheshire.core :refer :all]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.organizer]))

  ; (defn- with-handler [f]
  ;   (jdbc/with-db-transaction [tx embedded-db/db-spec]
  ;     (println "with-handler")
  ;     (jdbc/db-set-rollback-only! tx)
  ;       (let [db (duct.database.sql/->Boundary tx)
  ;             handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db}))]
  ;             (f))))

  (use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))
  
  (defn- send-request [tx request]
    (jdbc/db-set-rollback-only! tx)
    (let [db (duct.database.sql/->Boundary tx)
          handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db}))]
          (handler request)))

  (def organization {:oid "1.2.3.4"
                     :agreement_start_date "2018-01-01"
                     :agreement_end_date "2029-01-01"
                     :contact_email "fuu@bar.com"
                     :contact_name "fuu"
                     :contact_phone_number "123456"})

  (defn- insert-organization-statement [oid]
    (str "INSERT INTO organizer VALUES (" oid ", '2018-01-01', '2019-01-01', 'name', 'email', 'phone')"))

  (deftest add-organization-test
    (jdbc/with-db-transaction [tx embedded-db/db-spec]
      (let [json-body (generate-string organization)
            request (-> (mock/request :post "/organizer" json-body)
                        (mock/content-type "application/json; charset=UTF-8"))
            response (send-request tx request)]
        (testing "post organization endpoint should add organization"
          (is (= '({:count 1})
            (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
          (is (= (:status response) 200))))))

  (deftest get-organizations-test
    (jdbc/with-db-transaction [tx embedded-db/db-spec]
      (jdbc/execute! tx (insert-organization-statement "'1.2.3.4'"))
      (jdbc/execute! tx (insert-organization-statement "'1.2.3.5'"))
      (let [request (-> (mock/request :get "/organizer"))
            response (send-request tx request)
            response-body (parse-string (slurp (:body response) :encoding "UTF-8"))]
        (testing "get organizations endpoint should return 2 organizations"
          (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8")))
          (is (= (:status response) 200))
          (is (= (count (get response-body "organizations")))))))

  (deftest delete-organization-test
    (jdbc/with-db-transaction [tx embedded-db/db-spec]
      (jdbc/execute! tx (insert-organization-statement "'1.2.3.4'"))
      (let [request (-> (mock/request :delete "/organizer/1.2.3.4"))
            response (send-request tx request)]
        (testing "delete organization endpoint should remove organization"
          (is (= (:status response) 200))
          (is (= '({:count 0})
            (jdbc/query tx "SELECT COUNT(1) FROM organizer")))))))