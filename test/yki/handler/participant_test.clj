(ns yki.handler.participant-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(deftest exam-session-participants-test
  (testing "get exam session participants endpoint should return participant registration form and state"
    (base/insert-login-link-prereqs)
    (base/insert-registrations "COMPLETED")
    (let [exam-session-id (base/get-exam-session-id)
          request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration"))
          response (base/send-request-with-tx request)
          response-body (base/body-as-json response)]
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200))
      (is (= response-body (j/read-value (slurp "test/resources/participants.json")))))))

(deftest exam-session-participant-delete-test
  (testing "delete exam session participant should set registration to state to CANCELLED"
    (base/insert-login-link-prereqs)
    (base/insert-registrations "SUBMITTED")
    (let [exam-session-id (base/get-exam-session-id)
          registration-id (:id (base/select-one "SELECT id from registration"))
          request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration/" registration-id))
          response (base/send-request-with-tx request)
          registration-state (:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
      (is (= (:status response) 200))
      (is (= registration-state "CANCELLED")))))

