(ns yki.handler.file-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [clojure.java.io :as io]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing])
  (:import [java.io File]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- create-temp-file [file-path]
  (let [filename-start (inc (.lastIndexOf file-path "/"))
        file-extension-start (.lastIndexOf file-path ".")
        file-name (subs file-path filename-start file-extension-start)
        file-extension (subs file-path file-extension-start)
        temp-file (File/createTempFile file-name file-extension)]
    (io/copy (io/file file-path) temp-file)
    temp-file))

(deftest upload-file-test
  (base/insert-organizer "'1.2.3.5'")
  (with-routes!
    {"/liiteri/api/files" {:status 200 :content-type "application/json"
                           :body   (j/write-value-as-string {:key "d45c5262"})}}
    (let [filecontent {:tempfile (create-temp-file "test/resources/test.pdf")
                       :content-type "application/pdf",
                       :filename "test.pdf"}
          request (assoc (mock/request :post (str routing/organizer-api-root "/1.2.3.5/file"))
                         :params {:filecontent filecontent}
                         :multipart-params {"file" filecontent})
          response (base/send-request-with-tx request port)
          response-body (j/read-value (slurp (:body response) :encoding "UTF-8"))]
      (testing "post files endpoint should send file to file store and save returned id to database"
        (is (= {:count 1}
               (base/select-one "SELECT COUNT(1) FROM attachment_metadata WHERE external_id = 'd45c5262'")))
        (is (= {"external_id" "d45c5262"} response-body))))))

(deftest get-file-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-organizer "'1.2.3.5'")
  (base/insert-attachment-metadata "'1.2.3.5'")
  (let [request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/file/a0d5dfc2"))
        response (base/send-request-with-tx request "")]
    (testing "get file should return 404 when attachment has different organizer"
      (is (= (:status response) 404)))))
