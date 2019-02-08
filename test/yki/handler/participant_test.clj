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
    (base/insert-base-data)
    (base/insert-registrations "COMPLETED")

    (let [exam-session-id (base/get-exam-session-id)
          request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration"))
          response (base/send-request-with-tx request)
          response-body (base/body-as-json response)]
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200))
      (is (= response-body (j/read-value (slurp "test/resources/participants.json"))))

      (base/insert-exam-session 2 "'1.2.3.4'" 5)
      (testing "participant registration is changed to another exam session"
        (let [registration-id (:id (base/select-one "SELECT id from registration"))
              relocate-request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration/" registration-id "/relocate")
                                                 (j/write-value-as-string {:to_exam_session_id 2}))
                                   (mock/content-type "application/json; charset=UTF-8"))
              not-found-request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration/" registration-id "/relocate")
                                                  (j/write-value-as-string {:to_exam_session_id 3}))
                                    (mock/content-type "application/json; charset=UTF-8"))
              relocate-response (base/send-request-with-tx relocate-request)
              not-found-response (base/send-request-with-tx not-found-request)
              new-exam-session-id (:exam_session_id (base/select-one (str "SELECT exam_session_id from registration where id=" registration-id)))]
          (is (= new-exam-session-id 2))
          (is (= (:status not-found-response) 404))
          (is (= (:status relocate-response) 200)))))))

(deftest exam-session-participant-delete-test
  (testing "delete exam session participant should set registration to state to CANCELLED"
    (base/insert-base-data)
    (base/insert-registrations "SUBMITTED")
    (let [exam-session-id (base/get-exam-session-id)
          registration-id (:id (base/select-one "SELECT id from registration"))
          request (mock/request :delete (str routing/organizer-api-root "/1.2.3.4/exam-session/" exam-session-id "/registration/" registration-id))
          response (base/send-request-with-tx request)
          registration-state (:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
      (is (= (:status response) 200))
      (is (= registration-state "CANCELLED")))))
