(ns yki.handler.exam-session-public-test
  (:require
    [clojure.test :refer [deftest use-fixtures join-fixtures testing is]]
    [compojure.api.sweet :refer [api]]
    [duct.database.sql]
    [integrant.core :as ig]
    [ring.mock.request :as mock]
    [yki.boundary.exam-session-db]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.exam-session-public]
    [yki.handler.routing :as routing]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))
(use-fixtures :each embedded-db/with-transaction)

(defn- send-request
  ([request]
   (send-request request (base/environment "prod")))
  ([request env]
   (let [handler (api (ig/init-key :yki.handler/exam-session-public {:db             (base/db)
                                                                     :environment    env
                                                                     :payment-config {:amount {:PERUS 100
                                                                                               :KESKI 123
                                                                                               :YLIN  160}}}))]
     (handler request))))

(deftest get-exam-sessions-test-with-coercions-enabled
  (base/insert-base-data)
  ; Exam sessions listing endpoint has coercions turned on only in dev and qa environments due to performance issues.
  (let [env (base/environment "dev")]
    (testing "get exam sessions endpoint should return 200 along with a list of upcoming exam sessions"
      (let [request       (mock/request :get routing/exam-session-public-api-root)
            response      (send-request request env)
            response-body (base/body-as-json response)
            exam-sessions (response-body "exam_sessions")]
        (is (= (:status response) 200))
        ; Initially, the single exam session is in the past
        (is (= (count exam-sessions) 0)))
      ; Update exam session to future and verify that endpoint now returns exam sessions
      (let [future-exam-date (base/select-one "SELECT id from exam_date where exam_date >= current_timestamp;")
            _                (base/execute! (str "UPDATE exam_session SET exam_date_id=" (:id future-exam-date) ";"))
            request          (mock/request :get routing/exam-session-public-api-root)
            response         (send-request request env)
            response-body    (base/body-as-json response)
            exam-sessions    (response-body "exam_sessions")]
        (is (= (:status response) 200))
        (is (= (count exam-sessions) 1))
        (is (= ((first exam-sessions) "exam_fee") 100))))))

(deftest get-exam-sessions-test
  (base/insert-base-data)
  (testing "get exam sessions endpoint should return 200 along with a list of upcoming exam sessions"
    (let [request       (mock/request :get routing/exam-session-public-api-root)
          response      (send-request request)
          response-body (base/body-as-json response)
          exam-sessions (response-body "exam_sessions")]
      (is (= (:status response) 200))
      ; Initially, the single exam session is in the past
      (is (= (count exam-sessions) 0)))
    ; Update exam session to future and verify that endpoint now returns exam sessions
    (let [future-exam-date (base/select-one "SELECT id from exam_date where exam_date >= current_timestamp;")
          _                (base/execute! (str "UPDATE exam_session SET exam_date_id=" (:id future-exam-date) ";"))
          request          (mock/request :get routing/exam-session-public-api-root)
          response         (send-request request)
          response-body    (base/body-as-json response)
          exam-sessions    (response-body "exam_sessions")]
      (is (= (:status response) 200))
      (is (= (count exam-sessions) 1))
      (is (= ((first exam-sessions) "exam_fee") 100))))

  (let [request       (mock/request :get (str routing/exam-session-public-api-root "/1"))
        response      (send-request request)
        response-body (base/body-as-json response)]
    (testing "get exam session endpoint should return 200"
      (is (= (response-body "exam_fee") 100))
      (is (= (response-body "participants") 0))
      (is (= (:status response) 200))))

  (let [request  (mock/request :get (str routing/exam-session-public-api-root "/123"))
        response (send-request request)]
    (testing "get status endpoint should return 404"
      (is (= (:status response) 404)))))
