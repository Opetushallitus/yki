(ns yki.handler.organizer-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [ring.mock.request :as mock]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest organizer-validation-test
  (let [json-body (j/write-value-as-string (assoc-in base/organizer [:agreement_start_date] "NOT_A_VALID_DATE"))
        request   (-> (mock/request :post (str routing/organizer-api-root) json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
        response  (base/send-request-with-tx request)]
    (testing "post organizer endpoint should return 400 status code for validation errors"
      (is (= {:count 0}
             (base/select-one "SELECT COUNT(1) FROM organizer")))
      (is (= (:status response) 400)))))

(deftest update-organizer-test
  (let [organizer-oid "1.2.3.5"
        _             (base/insert-organizer organizer-oid)
        updated-email "new-email@test.invalid"
        json-body     (j/write-value-as-string (assoc base/organizer :contact_email updated-email))
        request       (-> (mock/request :put (str routing/organizer-api-root "/" organizer-oid) json-body)
                          (mock/content-type "application/json; charset=UTF-8"))
        response      (base/send-request-with-tx request)]
    (testing "put organization endpoint should update organization based on oid in url params"
      (is (= {:count 2}
             (base/select-one
               (str "SELECT COUNT(1) FROM exam_language where organizer_id = (SELECT id FROM organizer WHERE oid = '"
                    organizer-oid
                    "' AND deleted_at IS NULL)"))))
      (is (= {:contact_name "fuu"}
             (base/select-one (str "SELECT contact_name FROM organizer where oid = '" organizer-oid "'"))))
      (is (= {:contact_email updated-email}
             (base/select-one (str "SELECT contact_email FROM organizer where oid = '" organizer-oid "'"))))
      (is (= (:status response) 200)))))

(deftest add-organizer-test
  (let [json-body (j/write-value-as-string base/organizer)
        request   (-> (mock/request :post routing/organizer-api-root json-body)
                      (mock/content-type "application/json; charset=UTF-8"))
        response  (base/send-request-with-tx request)]
    (testing "post organizer endpoint should add organizer"
      (is (= {:count 1}
             (base/select-one "SELECT COUNT(1) FROM organizer")))
      (is (= (:status response) 200)))))

(deftest get-organizers-test
  (let [organizer-oid "1.2.3.4"]
    (base/insert-organizer organizer-oid)
    (base/insert-organizer "1.2.3.5")
    (base/insert-languages organizer-oid))
  (let [request  (mock/request :get routing/organizer-api-root)
        response (base/send-request-with-tx request)
        _        (base/body-as-json response)]
    (testing "get organizers endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest delete-organizer-test
  (base/insert-base-data)
  (let [organizer-oid (:oid base/organizer)
        office-oid    (str organizer-oid ".5")
        request       (mock/request :delete (str routing/organizer-api-root "/" organizer-oid))
        response      (base/send-request-with-tx request)
        data-sync-q   (base/data-sync-q)
        sync-req-1    (pgq/take data-sync-q)
        sync-req-2    (pgq/take data-sync-q)]
    (testing "delete organizer endpoint should mark organizer deleted in db and not delete payment config"
      (is (= (:status response) 200))
      (is (= {:count 0}
             (base/select-one "SELECT COUNT(1) FROM organizer where deleted_at IS NULL")))
      (is (= {:count 1}
             (base/select-one "SELECT COUNT(1) FROM organizer where deleted_at IS NOT NULL"))))

    (testing "delete organizer endpoint should send organizer and office oids to sync queue"
      (is (= (:type sync-req-1) "DELETE"))
      (is (= (:organizer-oid sync-req-1) organizer-oid))
      (is (= (:type sync-req-2) "DELETE"))
      (is (= (:organizer-oid sync-req-2) office-oid)))))
