(ns yki.handler.file-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [yki.handler.base-test :as base]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [yki.boundary.files :as files]
            [muuntaja.middleware :as middleware]
            [stub-http.core :refer :all]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.file]
            [yki.handler.organizer]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(defn- create-temp-file [file-path]
  (let [filename-start (inc (.lastIndexOf file-path "/"))
        file-extension-start (.lastIndexOf file-path ".")
        file-name (.substring file-path filename-start file-extension-start)
        file-extension (.substring file-path file-extension-start)
        temp-file (java.io.File/createTempFile file-name file-extension)]
    (io/copy (io/file file-path) temp-file)
    temp-file))

(deftest upload-file-test
  (jdbc/with-db-transaction [tx embedded-db/db-spec]
    (base/insert-organization tx "'1.2.3.5'")
    (with-routes!
      {"/liiteri/api/files" {:status 200 :content-type "application/json"
                     :body   (j/write-value-as-string {:key "d45c5262"})}}
      (let [filecontent {:tempfile (create-temp-file "test/resources/test.pdf")
                         :content-type "application/pdf",
                         :filename "test.pdf"}
            request (assoc (mock/request :post (str routing/organizer-api-root "/1.2.3.5/file"))
                           :params {:filecontent filecontent}
                           :multipart-params {"file" filecontent})
            response (base/send-request tx request port)
            response-body (j/read-value (slurp (:body response) :encoding "UTF-8"))]
        (testing "post files endpoint should send file to file store and save returned id to database"
          (is (= '({:count 1})
                 (jdbc/query tx "SELECT COUNT(1) FROM attachment_metadata WHERE external_id = 'd45c5262'")))
          (is (= {"external_id" "d45c5262"} response-body)))))))