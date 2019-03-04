(ns yki.handler.organizer-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [muuntaja.middleware :as middleware]
            [muuntaja.core :as m]
            [clojure.java.jdbc :as jdbc]
            [pgqueue.core :as pgq]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.handler.file]
            [yki.handler.organizer]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest organizer-validation-test
  (let [json-body (j/write-value-as-string (assoc-in base/organizer [:agreement_start_date] "NOT_A_VALID_DATE"))
        request (-> (mock/request :post (str routing/organizer-api-root) json-body)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)]
    (testing "post organizer endpoint should return 400 status code for validation errors"
      (is (= '({:count 0})
             (base/select "SELECT COUNT(1) FROM organizer")))
      (is (= (:status response) 400)))))

(deftest update-organizer-test
  (base/insert-organizer "'1.2.3.5'")
  (let [json-body (j/write-value-as-string (assoc base/organizer :merchant {:merchant_id 2 :merchant_secret "SECRET2"}))
        request (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.5") json-body)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)]
    (testing "put organization endpoint should update organization based on oid in url params"
      (is (= '({:count 2})
             (base/select "SELECT COUNT(1) FROM exam_language where organizer_id = (SELECT id FROM organizer WHERE oid = '1.2.3.5' AND deleted_at IS NULL)")))
      (is (= '({:contact_name "fuu"})
             (base/select "SELECT contact_name FROM organizer where oid = '1.2.3.5'")))
      (is (= '({:merchant_secret "SECRET2"})
             (jdbc/query @embedded-db/conn "SELECT merchant_secret FROM payment_config where merchant_id = 2")))
      (is (= (:status response) 200)))))

(deftest add-organizer-test
  (let [json-body (j/write-value-as-string base/organizer)
        request (-> (mock/request :post (str routing/organizer-api-root) json-body)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)]
    (testing "post organizer endpoint should add organizer"
      (is (= '({:count 1})
             (base/select "SELECT COUNT(1) FROM organizer")))
      (is (= '({:count 1})
             (base/select "SELECT COUNT(1) FROM payment_config")))
      (is (= (:status response) 200)))))

(deftest get-organizers-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-organizer "'1.2.3.5'")
  (base/insert-languages "'1.2.3.4'")
  (let [request (mock/request :get routing/organizer-api-root)
        response (base/send-request-with-tx request)
        response-body (base/body-as-json response)]
    (testing "get organizers endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest delete-organizer-test
  (base/insert-base-data)
  (let [request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4"))
        response (base/send-request-with-tx request)
        data-sync-q (base/data-sync-q)
        sync-req-1 (pgq/take data-sync-q)
        sync-req-2 (pgq/take data-sync-q)]
    (testing "delete organizer endpoint should mark organizer deleted in db and delete payment config"
      (is (= (:status response) 200))
      (is (= '({:count 0})
             (base/select "SELECT COUNT(1) FROM organizer where deleted_at IS NULL")))
      (is (= '({:count 1})
             (base/select "SELECT COUNT(1) FROM organizer where deleted_at IS NOT NULL")))
      (is (= '({:count 0})
             (base/select "SELECT COUNT (1) FROM payment_config"))))

    (testing "delete organizer endpoint should send organizer and office ois to sync queue"
      (is (= (:type sync-req-1) "DELETE"))
      (is (= (:organizer-oid sync-req-1) "1.2.3.4"))
      (is (= (:type sync-req-2) "DELETE"))
      (is (= (:organizer-oid sync-req-2) "1.2.3.4.5")))))
