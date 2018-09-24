(ns yki.handler.organizer-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [yki.boundary.files :as files]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.files]
            [yki.handler.organizer]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(defrecord MockStore []
  files/FileStore
  (upload-file [_ _ _]
    {"key" "d45c5262"}))

(defn- send-request [tx request]
  (jdbc/db-set-rollback-only! tx)
  (let [db (duct.database.sql/->Boundary tx)
        files-handler (ig/init-key :yki.handler/files {:db db :file-store (->MockStore)})
        handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                            :url-helper {}
                                                                            :files-handler files-handler}))]
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

(defn- create-temp-file [file-path]
  (let [filename-start (inc (.lastIndexOf file-path "/"))
        file-extension-start (.lastIndexOf file-path ".")
        file-name (.substring file-path filename-start file-extension-start)
        file-extension (.substring file-path file-extension-start)
        temp-file (java.io.File/createTempFile file-name file-extension)]
    (io/copy (io/file file-path) temp-file)
    temp-file))

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

(deftest upload-file-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (insert-organization tx "'1.2.3.5'")
    (let [filecontent {:tempfile (create-temp-file "test/resources/test.pdf")
                       :content-type "application/pdf",
                       :filename "test.pdf"}
          request (assoc (mock/request :post (str routing/organizer-api-root "/1.2.3.5/files"))
                         :params {:filecontent filecontent}
                         :multipart-params {"file" filecontent})
          response (send-request tx request)
          response-body (j/read-value (slurp (:body response) :encoding "UTF-8"))]
      (testing "post files endpoint should send file to file store and save returned id to database"
        (is (= '({:count 1})
               (jdbc/query tx "SELECT COUNT(1) FROM attachment_metadata WHERE external_id = 'd45c5262'")))
        (is (= {"external_id" "d45c5262"} response-body) )))))
