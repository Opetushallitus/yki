(ns yki.handler.organizer-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.organizer]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(defn- send-request [tx request]
  (jdbc/db-set-rollback-only! tx)
  (let [db (duct.database.sql/->Boundary tx)
        handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db}))]
    (handler request)))

(def organization {:oid "1.2.3.4"
                   :agreement_start_date "2018-01-01T00:00:00Z"
                   :agreement_end_date "2029-01-01T00:00:00Z"
                   :contact_email "fuu@bar.com"
                   :contact_name "fuu"
                   :contact_phone_number "123456"
                   :languages [{:language_code "fi" :level_code "PERUS"},
                               {:language_code "en" :level_code "PERUS"}]})

(def organizations-json
  (j/read-value (slurp "test/resources/organizers.json")))

(defn- insert-organization [tx oid]
  (jdbc/execute! tx (str "INSERT INTO organizer (oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number, contact_shared_email)
      VALUES (" oid ", '2018-01-01', '2019-01-01', 'name', 'email@oph.fi', 'phone', 'shared@oph.fi')")))

(defn- insert-languages [tx oid]
  (jdbc/execute! tx (str "insert into exam_language (language_code, level_code, organizer_id) values ('fi', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))"))
  (jdbc/execute! tx (str "insert into exam_language (language_code, level_code, organizer_id) values ('sv', 'PERUS', (SELECT id FROM organizer WHERE oid = " oid " AND deleted_at IS NULL))")))

(deftest organizer-validation-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (let [json-body (j/write-value-as-string (assoc-in organization [:agreement_start_date] "NOT_A_VALID_DATE"))
          request (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (send-request tx request)]
      (testing "post organization endpoint should return 400 status code for validation errors"
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
        (is (= (:status response) 400))))))

(deftest update-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (insert-organization tx "'1.2.3.5'")
    (let [json-body (j/write-value-as-string organization)
          request (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.5") json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (send-request tx request)]
      (testing "put organization endpoint should update organization based on oid in url params"
        (is (= '({:count 2})
               (jdbc/query tx "SELECT COUNT(1) FROM exam_language where organizer_id = (SELECT id FROM organizer WHERE oid = '1.2.3.5' AND deleted_at IS NULL)")))
        (is (= '({:contact_name "fuu"})
               (jdbc/query tx "SELECT contact_name FROM organizer where oid = '1.2.3.5'")))
        (is (= (:status response) 200))))))

(deftest add-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (let [json-body (j/write-value-as-string organization)
          request (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (send-request tx request)]
      (testing "post organization endpoint should add organization"
        (is (= '({:count 1})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer")))
        (is (= (:status response) 200))))))

(deftest get-organizations-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (insert-organization tx "'1.2.3.4'")
    (insert-organization tx "'1.2.3.5'")
    (insert-languages tx "'1.2.3.4'")
    (let [request (-> (mock/request :get routing/organizer-api-root))
          response (send-request tx request)
          response-body (j/read-value (slurp (:body response) :encoding "UTF-8"))]
      (testing "get organizations endpoint should return 2 organizations with exam levels"
        (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8")))
      (is (= (:status response) 200))
      (is (= response-body organizations-json)))))

(deftest delete-organization-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (insert-organization tx "'1.2.3.4'")
    (let [request (-> (mock/request :delete (str routing/organizer-api-root "/1.2.3.4")))
          response (send-request tx request)]
      (testing "delete organization endpoint should remove organization"
        (is (= (:status response) 200))
        (is (= '({:count 0})
               (jdbc/query tx "SELECT COUNT(1) FROM organizer where deleted_at IS NULL")))))))
