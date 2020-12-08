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

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- get-exam-date [response-body date]
  (let [exam-dates (fn [r] (get-in r ["session_date"]))
        exam-sessions (get-in response-body ["exam_sessions"])
        date-map (map exam-dates exam-sessions)
        get-date (fn [r] (some #{r} date-map))]
    (get-date date)))

(defn- get-new-entry [orig-entry new-values]
  (reduce-kv (fn [entry k v]
               (base/change-entry entry (name k) v))
             orig-entry new-values))

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
  (base/insert-post-admission-dates)

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
      (is (= (:type sync-req) "DELETE"))))

  ; '2039-05-02' no post admission
  ; '2041-06-01' post admission, disabled
  ; '2041-07-01' post admission, enabled
  (let [exam-session-route (str routing/organizer-api-root "/1.2.3.4/exam-session")]
    (letfn [(mock-request [method path body]  (-> (mock/request method path body)
                                                  (mock/content-type "application/json; charset=UTF-8")
                                                  (base/send-request-with-tx)))
            (create-pa-enabled-es []          (->> (get-new-entry base/exam-session {:session_date "2041-07-01"})
                                                   (mock-request :post (str exam-session-route))
                                                   (base/body-as-json)))
            (create-pa-disabled-es []         (->> (get-new-entry base/exam-session {:session_date "2041-06-01"})
                                                   (mock-request :post (str exam-session-route))
                                                   (base/body-as-json)))
            (activate [exam-session-id entry] (-> (mock-request :post (str exam-session-route "/" exam-session-id "/post-admission/activate") entry)))
            (deactivate [exam-session-id]     (-> (mock-request :post (str exam-session-route "/" exam-session-id "/post-admission/deactivate") "{}")))]

      (testing "can activate a exam session post admission"
        (let [exam-session-response (create-pa-enabled-es)
              exam-session-id (get exam-session-response "id")
              response (activate exam-session-id (get-new-entry "{}" {:post_admission_quota 5}))
              exam-session (base/select-one (str "SELECT * FROM exam_session WHERE id = " exam-session-id))]
          (is (= (:status response) 200))
          (is (= (:post_admission_active exam-session) true))
          (is (= (:post_admission_quota exam-session) 5))))

      (testing "cannot activate post admission if post admissions are not enabled for that date"
        (let [exam-session-response (create-pa-disabled-es)
              exam-session-id (get exam-session-response "id")
              response (activate exam-session-id (get-new-entry "{}" {:post_admission_quota 5}))
              exam-session (base/select-one (str "SELECT * FROM exam_session WHERE id = " exam-session-id))]
          (is (= (:status response) 409))
          (is (= (:post_admission_active exam-session) false))
          (is (= (:post_admission_quota exam-session) nil))))

      (testing "can disable post admissions for exam session"
        (let [exam-session-id (:id (base/select-one (base/select-exam-session-by-date "2041-07-01")))
              response (deactivate exam-session-id)
              exam-session (base/select-one (str "SELECT * FROM exam_session WHERE id = " exam-session-id))]
          (is (= (:status response) 200))
          (is (= (:post_admission_active exam-session) false)))))))

(deftest exam-session-history-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-languages "'1.2.3.4'")

  (let [exam-dates [["2040-06-01" "2040-03-01" "2040-05-30"]
                    ["2040-04-01" "2040-03-01" "2040-03-30"]
                    ["2039-12-01" "2040-11-01" "2040-11-30"]
                    ["2039-10-01" "2039-10-01" "2039-10-30"]]
        date-id (fn [date] (-> date
                               (base/select-exam-date-id-by-date)
                               (base/select-one)
                               :id))
        insert-dates (fn [dates] (let [[exam-date reg-start reg-end] dates]
                                   (base/insert-custom-exam-date exam-date reg-start reg-end)
                                   (base/insert-exam-session (date-id exam-date) "'1.2.3.4'" 5)
                                   (base/insert-exam-session-location-by-date exam-date "fi")))
        iterate-exam-dates (fn [dates] (for [d dates] (insert-dates d)))]
    (doall (iterate-exam-dates exam-dates)))

  (testing "exam session endpoint with 'days' parameter should return sessions from past days"
    (let [request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-session?from=2040-06-01T00:00:00Z&days=100"))
          response (base/send-request-with-tx request)
          response-body (base/body-as-json response)]
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200))
      (is (= (get-exam-date response-body "2040-04-01") "2040-04-01"))
      (is (= (get-exam-date response-body "2039-12-01") nil)))))

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

(deftest exam-session-delete-fail-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (let [request             (mock/request :delete (str routing/organizer-api-root "/1.2.3.4/exam-session/1"))
        response             (base/send-request-with-tx request)]
    (testing "should not allow deleting exam session with participants"
      (is (= (:status response) 409)))))
