(ns yki.handler.exam-session-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [pgqueue.core :as pgq]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.util.url-helper]))

; (use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(deftest exam-session-validation-test
  (let [invalid-exam-session (base/change-entry base/exam-session "session_date" "NOT_A_DATE")
        request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") invalid-exam-session)
                    (mock/content-type "application/json; charset=UTF-8"))
        response (base/send-request-with-tx request)
        response-body (base/body-as-json response)]
    (testing "post exam session endpoint should return 400 status code for validation errors"
      (is (= {:count 0}
             (base/select-one "SELECT COUNT(1) FROM exam_session")))
      (is (= (:status response) 400)))))

(deftest exam-session-crud-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-languages "'1.2.3.4'")
  (base/insert-exam-dates)

  (testing "post exam session endpoint should add valid exam session to database and send sync request to queue"
    (let [request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session") base/exam-session)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request-with-tx request)
          response-body (base/body-as-json response)
          data-sync-q (base/data-sync-q)
          sync-req (pgq/take data-sync-q)]
      (is (= {:count 1}
             (base/select-one "SELECT COUNT(1) FROM exam_session")))
      (is (= {:count 3}
             (base/select-one "SELECT COUNT(1) FROM exam_session_location")))
      (is (= (:status response) 200))
      (is (some? (:exam-session sync-req)))
      (is (= (:type sync-req) "CREATE"))))

  (testing "get exam session endpoint should return exam session with location"
    (let [request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-session"))
          response (base/send-request-with-tx request)
          response-body (base/body-as-json response)]
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200))
      (is (= response-body base/exam-sessions-json))))

  (testing "put exam session endpoint should update exam session based on id query parameter"
    (let [updated-exam-session (base/change-entry base/exam-session "max_participants" 51)
          request (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.4/exam-session/1") updated-exam-session)
                      (mock/content-type "application/json; charset=UTF-8"))
          response (base/send-request-with-tx request)]
      (is (= {:max_participants 51}
             (base/select-one "SELECT max_participants FROM exam_session where id = 1")))
      (is (= (:status response) 200))))

  (testing "delete exam session endpoint should remove exam session and it's location"
    (let [request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4/exam-session/1"))
          response (base/send-request-with-tx request)
          data-sync-q (base/data-sync-q)
          sync-req (pgq/take data-sync-q)]
      (is (= (:status response) 200))
      (is (= {:count 0}
             (base/select-one "SELECT COUNT(1) FROM exam_session")))
      (is (= {:count 0}
             (base/select-one "SELECT COUNT(1) FROM exam_session_location")))
      (is (some? (:exam-session sync-req)))
      (is (= (:type sync-req) "DELETE")))))

(deftest exam-session-update-max-participants-fail-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (let [updated-exam-session (base/change-entry base/exam-session "max_participants" 1)
        request              (-> (mock/request :put (str routing/organizer-api-root "/1.2.3.4/exam-session/1") updated-exam-session)
                                 (mock/content-type "application/json; charset=UTF-8"))
        response             (base/send-request-with-tx request)]
    (testing "should not allow setting max participants lower than current participants"
      (is (= (:status response) 409))
      (is (= {:max_participants 5}
             (base/select-one "SELECT max_participants FROM exam_session where id = 1"))))))

