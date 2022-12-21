(ns yki.handler.exam-date-test
  (:require
    [clojure.test :refer [deftest is join-fixtures testing use-fixtures]]
    [ring.mock.request :as mock]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(def new-exam-date
  {:exam_date               "2050-02-01"
   :registration_start_date "2050-01-01"
   :registration_end_date   "2050-01-12"
   :languages               [{:language_code "fin" :level_code "YLIN"}
                             {:language_code "eng" :level_code "YLIN"}]})

(def exam-date-url (str routing/organizer-api-root "/" (:oid base/organizer) "/exam-date"))

(defn- request-post-exam-date [exam-date]
  (let [request (-> (mock/request :post exam-date-url)
                    (mock/json-body exam-date))]
    (base/send-request-with-tx request)))

(defn- request-get-exam-date [id]
  (let [request (mock/request :get (str exam-date-url "/" id))]
    (base/send-request-with-tx request)))

(defn- request-put-exam-date [id exam-date]
  (let [request (-> (mock/request :put (str exam-date-url "/" id))
                    (mock/json-body exam-date))]
    (base/send-request-with-tx request)))

(defn- request-delete-exam-date [id]
  (let [request (mock/request :delete (str exam-date-url "/" id))]
    (base/send-request-with-tx request)))

(deftest list-exam-dates-test
  (base/insert-organizer (:oid base/organizer))

  (testing "GET list exam dates returns 200"
    (let [request  (mock/request :get exam-date-url)
          response (base/send-request-with-tx request)]
      (is (= (:status response) 200)))))

(deftest post-exam-date-test
  (base/insert-organizer (:oid base/organizer))

  (testing "POST with valid data inserts exam date and languages"
    (let [response      (request-post-exam-date new-exam-date)
          response-body (base/body-as-json response)
          id            (get-in response-body ["id"])
          languages     (base/select (base/select-exam-date-languages-by-date-id id))]
      (is (= (:status response) 200))
      (is (= 2 (count languages)))))

  (testing "POST fails for invalid period"
    (let [response (request-post-exam-date (assoc new-exam-date :exam_date "2050-01-11"))]
      (is (= (:status response) 409))))

  (testing "POST fails if trying to insert an exam date with an existing date"
    (let [response (request-post-exam-date new-exam-date)]
      (is (= (:status response) 409)))))

(deftest get-exam-date-test
  (base/insert-organizer (:oid base/organizer))

  (testing "GET for existing exam event returns 200"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2051-01-01"))
          id              (get-in (base/body-as-json create-response) ["id"])
          get-response    (request-get-exam-date id)]
      (is (= (:status get-response) 200))))

  (testing "GET for deleted exam event returns 404"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2052-01-01"))
          id              (get-in (base/body-as-json create-response) ["id"])
          _               (request-delete-exam-date id)
          get-response    (request-get-exam-date id)]
      (is (= (:status get-response) 404)))))

(deftest put-exam-date-test
  (base/insert-organizer (:oid base/organizer))

  (testing "PUT with valid data updates exam date and its languages"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2050-02-02"))
          id              (get-in (base/body-as-json create-response) ["id"])
          update-body     {:exam_date                 "2050-03-15"
                           :registration_start_date   "2050-01-20"
                           :registration_end_date     "2050-02-14"
                           :post_admission_enabled    true
                           :post_admission_start_date "2050-02-23"
                           :post_admission_end_date   "2050-03-05"
                           :languages                 [{:language_code "fin" :level_code "PERUS"}
                                                       {:language_code "fin" :level_code "YLIN"}
                                                       {:language_code "eng" :level_code "PERUS"}]}
          update-response (request-put-exam-date id update-body)
          exam-date       (base/select-one (base/select-exam-date id))
          languages       (base/select (base/select-exam-date-languages-by-date-id id))]
      (is (= (:status update-response) 200))
      (is (= "2050-03-15" (:exam_date exam-date)))
      (is (= "2050-01-20" (:registration_start_date exam-date)))
      (is (= "2050-02-14" (:registration_end_date exam-date)))
      (is (= true (:post_admission_enabled exam-date)))
      (is (= "2050-02-23" (:post_admission_start_date exam-date)))
      (is (= "2050-03-05" (:post_admission_end_date exam-date)))
      (is (= 3 (count languages)))))

  (testing "PUT can be used to update period for an exam date with exam sessions"
    (let [create-body     (assoc new-exam-date :exam_date "2050-02-06")
          create-response (request-post-exam-date create-body)
          id              (get-in (base/body-as-json create-response) ["id"])
          _               (base/insert-exam-session id (:oid base/organizer) 5)
          update-body     (assoc create-body :registration_end_date "2050-01-20")
          update-response (request-put-exam-date id update-body)
          exam-date       (base/select-one (base/select-exam-date id))]
      (is (= (:status update-response) 200))
      (is (= "2050-01-20" (:registration_end_date exam-date)))))

  (testing "PUT fails for invalid period"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2050-02-03"))
          id              (get-in (base/body-as-json create-response) ["id"])
          update-body     {:exam_date                 "2050-03-17"
                           :registration_start_date   "2050-01-20"
                           :registration_end_date     "2050-02-14"
                           :post_admission_enabled    true
                           :post_admission_start_date "2050-02-13"
                           :post_admission_end_date   "2050-03-05"
                           :languages                 [{:language_code "fin" :level_code "PERUS"}]}
          update-response (request-put-exam-date id update-body)]
      (is (= (:status update-response) 409))))

  (testing "PUT fails if another exam date with the same date already exists"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2050-02-04"))
          id              (get-in (base/body-as-json create-response) ["id"])
          update-response (request-put-exam-date id new-exam-date)]
      (is (= (:status update-response) 409))))

  (testing "PUT fails if trying to change date for an exam date with exam sessions"
    (let [create-body     (assoc new-exam-date :exam_date "2050-04-01")
          create-response (request-post-exam-date create-body)
          id              (get-in (base/body-as-json create-response) ["id"])
          _               (base/insert-exam-session id (:oid base/organizer) 5)
          update-body     (assoc create-body :exam_date "2050-04-02")
          update-response (request-put-exam-date id update-body)]
      (is (= (:status update-response) 409))))

  (testing "PUT fails if trying to change languages for an exam date with exam sessions"
    (let [create-body     (assoc new-exam-date :exam_date "2050-02-05")
          create-response (request-post-exam-date create-body)
          id              (get-in (base/body-as-json create-response) ["id"])
          _               (base/insert-exam-session id (:oid base/organizer) 5)
          update-body     (assoc create-body :languages [{:language_code "fin" :level_code "YLIN"}])
          update-response (request-put-exam-date id update-body)]
      (is (= (:status update-response) 409)))))

(deftest delete-exam-date-test
  (base/insert-organizer (:oid base/organizer))

  (testing "DELETE marks exam date and its languages deleted"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2053-01-01"))
          id              (get-in (base/body-as-json create-response) ["id"])
          delete-response (request-delete-exam-date id)
          exam-date       (base/select-one (base/select-exam-date id))
          languages       (base/select (base/select-exam-date-languages-by-date-id id))]
      (is (= (:status delete-response) 200))
      (is (not (nil? (:deleted_at exam-date))))
      (is (empty? languages))))

  (testing "DELETE fails if exam date by given id is not found"
    (let [delete-response (request-delete-exam-date 10000)]
      (is (= (:status delete-response) 404))))

  (testing "DELETE fails if exam sessions exist for exam date"
    (let [create-response (request-post-exam-date (assoc new-exam-date :exam_date "2054-01-01"))
          id              (get-in (base/body-as-json create-response) ["id"])
          _               (base/insert-exam-session id (:oid base/organizer) 5)
          delete-response (request-delete-exam-date id)]
      (is (= (:status delete-response) 409)))))
