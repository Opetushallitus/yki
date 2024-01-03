(ns yki.handler.exam-session-public-test
  (:require
    [clojure.test :refer [deftest use-fixtures join-fixtures testing is]]
    [compojure.api.sweet :refer [api]]
    [duct.database.sql]
    [integrant.core :as ig]
    [jsonista.core :as j]
    [ring.mock.request :as mock]
    [yki.boundary.exam-session-db]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.exam-session-public]
    [yki.handler.routing :as routing]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))
(use-fixtures :each embedded-db/with-transaction)

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/exam-session-public {:db             (base/db)
                                                                    :payment-config {:amount {:PERUS 100
                                                                                              :KESKI 123
                                                                                              :YLIN  160}}}))]
    (handler request)))

(deftest get-exam-sessions-test
  (base/insert-base-data)
  (let [request       (mock/request :get routing/exam-session-public-api-root)
        response      (send-request request)
        response-body (base/body-as-json response)
        exam-sessions (response-body "exam_sessions")]
    (testing "get exam sessions endpoint should return 200"
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

(deftest post-exam-session-queue-test
  (base/insert-base-data)
  (let [id                     (:id (base/select-one "SELECT id from exam_session"))
        enroll-into-queue!     (fn [exam-session-id email]
                                 (-> (mock/request :post (str routing/exam-session-public-api-root "/" exam-session-id "/queue?lang=sv")
                                                   (j/write-value-as-string {:email email}))
                                     (mock/content-type "application/json; charset=UTF-8")
                                     (send-request)))
        response               (enroll-into-queue! id "test@test.com")
        twice-response         (enroll-into-queue! id "Test@test.com")
        get-exam-session-queue #(base/select "SELECT * from exam_session_queue")]
    (testing "post exam session queue endpoint should add email to queue"
      (is (= (:status response) 200))
      (is (= (base/body-as-json response)
             {"success" true}))
      (is (= (count (get-exam-session-queue)) 1)))
    (testing "post with same email should return conflict"
      (is (= (:status twice-response) 409))
      (is (= (base/body-as-json twice-response)
             {"exists" true
              "full"   false})))
    (testing "post with unique email to full queue should return error"
      (let [queue-capacity 50]
        (doseq [i (range queue-capacity)]
          (base/execute!
            (str "INSERT INTO exam_session_queue (email, lang, exam_session_id) VALUES ("
                 "'test_" i "@test.invalid',"
                 "'fi',"
                 id
                 ");")))
        (is (< queue-capacity
               (count (get-exam-session-queue)))))
      (let [full-queue-response (enroll-into-queue! id "unique@test.invalid")]
        (is (= (:status full-queue-response) 409))
        (is (= (base/body-as-json full-queue-response)
               {"exists" false
                "full"   true}))))))
