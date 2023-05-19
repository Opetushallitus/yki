(ns yki.handler.participant-test
  (:require
    [clojure.test :refer [deftest use-fixtures testing is]]
    [clojure.string :as str]
    [jsonista.core :as j]
    [ring.mock.request :as mock]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]
    [yki.middleware.auth :as auth]))

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
          participants-response (base/body-as-json response)
          id->created           (->> (str "SELECT r.id, r.created FROM registration r WHERE r.exam_session_id=" (base/get-exam-session-id))
                                     (base/select)
                                     (map (juxt :id :created))
                                     (into {}))
          expected-response     (update
                                  (j/read-value (slurp "test/resources/participants.json"))
                                  "participants"
                                  #(map (fn [data]
                                          (let [registration-id (data "registration_id")]
                                            (assoc data "created" (str (id->created registration-id)))))
                                        %))]
      (is (= "application/json; charset=utf-8" (get (:headers response) "Content-Type")))
      (is (= 200 (:status response)))
      (is (= expected-response participants-response))

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
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (let [registration-id        (:id (base/select-one "SELECT id from registration"))
        exam-session-route     (get-exam-session-route)
        request                (mock/request :delete (str exam-session-route "/registration/" registration-id))
        get-registration-state #(:state (base/select-one (str "SELECT state from registration where id=" registration-id)))]
    (testing "cancelling paid registration should fail if cancelling user is not OPH admin"
      (with-redefs [auth/oph-admin-access (constantly false)]
        (let [response (base/send-request-with-tx request)]
          (is (= (:status response) 404))
          (is (= (get-registration-state) "COMPLETED")))))
    (testing "cancelling paid registration should set registration state to PAID_AND_CANCELLED if user is OPH admin"
      (with-redefs [auth/oph-admin-access (constantly true)]
        (let [response (base/send-request-with-tx request)]
          (is (= (:status response) 200))
          (is (= (get-registration-state) "PAID_AND_CANCELLED")))))))
