(ns yki.handler.participant-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [clojure.string :as str]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- get-exam-session-route []
  (str/join "/" [routing/organizer-api-root (:oid base/organizer) "exam-session" (base/get-exam-session-id)]))

(deftest exam-session-participants-test
  (testing "get exam session participants endpoint should return participant registration form and state"
    (base/insert-base-data)
    (base/insert-registrations "COMPLETED")
    (base/insert-unpaid-expired-registration)
    (let [exam-session-route    (get-exam-session-route)
          request               (mock/request :get (str exam-session-route "/registration"))
          response              (base/send-request-with-tx request)
          participants-response (base/body-as-json response)]
      (is (= "application/json; charset=utf-8" (get (:headers response) "Content-Type")))
      (is (= 200 (:status response)))
      (is (= (j/read-value (slurp "test/resources/participants.json"))
             participants-response))

      (base/insert-exam-session 2 (:oid base/organizer) 5)
      (testing "participant registration is changed to another exam session"
        (let [registration-id     (:id (base/select-one "SELECT id from registration"))
              relocate-request    (-> (mock/request :post (str exam-session-route "/registration/" registration-id "/relocate")
                                                    (j/write-value-as-string {:to_exam_session_id 2}))
                                      (mock/content-type "application/json; charset=UTF-8"))
              not-found-request   (-> (mock/request :post (str exam-session-route "/registration/" registration-id "/relocate")
                                                    (j/write-value-as-string {:to_exam_session_id 3}))
                                      (mock/content-type "application/json; charset=UTF-8"))
              relocate-response   (base/send-request-with-tx relocate-request)
              not-found-response  (base/send-request-with-tx not-found-request)
              registration        (base/select-one (str "SELECT * from registration where id=" registration-id))
              old-exam-session-id (:original_exam_session_id registration)
              new-exam-session-id (:exam_session_id registration)]
          (is (= old-exam-session-id 1))
          (is (= new-exam-session-id 2))
          (is (= (:status not-found-response) 404))
          (is (= (:status relocate-response) 200)))))))

(deftest exam-session-participant-delete-not-paid-test
  (testing "delete exam session participant should set registration state to CANCELLED when registration is not paid"
    (base/insert-base-data)
    (base/insert-registrations "SUBMITTED")
    (let [registration-id    (:id (base/select-one "SELECT id from registration"))
          exam-session-route (get-exam-session-route)
          request            (mock/request :delete (str exam-session-route "/registration/" registration-id))
          response           (base/send-request-with-tx request)
          registration-state (:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
      (is (= (:status response) 200))
      (is (= registration-state "CANCELLED")))))

(deftest exam-session-participant-delete-paid-test
  (testing "delete exam session participant should set registration state to PAID_AND_CANCELLED when registration is paid"
    (base/insert-base-data)
    (base/insert-registrations "COMPLETED")
    (let [registration-id    (:id (base/select-one "SELECT id from registration"))
          exam-session-route (get-exam-session-route)
          request            (mock/request :delete (str exam-session-route "/registration/" registration-id))
          response           (base/send-request-with-tx request)
          registration-state (:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
      (is (= (:status response) 200))
      (is (= registration-state "PAID_AND_CANCELLED")))))

(deftest confirm-payment-test
  (testing "confirm payment should set payment status to PAID and registration state to COMPLETED"
    (base/insert-base-data)
    (base/insert-payment)
    (let [registration-id          (:id (base/select-one "SELECT id from registration"))
          exam-session-route       (get-exam-session-route)
          confirm-payment-request  (-> (mock/request :post (str exam-session-route "/registration/" registration-id "/confirm-payment"))
                                       (mock/content-type "application/json; charset=UTF-8"))
          confirm-payment-response (base/send-request-with-tx confirm-payment-request)
          payment                  (base/select-one "SELECT * from payment")
          registration-state       (:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
      (is (= (:status confirm-payment-response) 200))
      (is (= (:state payment) "PAID"))
      (is (= registration-state "COMPLETED")))))
