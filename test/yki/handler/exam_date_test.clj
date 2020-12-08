(ns yki.handler.exam-date-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [yki.handler.base-test :as base]
            [compojure.api.sweet :refer :all]
            [jsonista.core :as j]
            [clojure.java.jdbc :as jdbc]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.exam-date-public]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- get-success-status [response-body]
  (get-in response-body ["success"]))

(defn- create-new-entry [orig-entry new-values]
  (reduce-kv (fn [entry k v]
               (base/change-entry entry (name k) v))
             orig-entry new-values))

(defn- create-exam-date-entry [new-values]
  (create-new-entry base/exam-date new-values))

(defn- get-exam-date-id-by-date [exam-date]
  (:id (base/select-one (base/select-exam-date-id-by-date exam-date))))

(defn- get-exam-date [id]
  (let [request (-> (mock/request :get (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id)))
                    (mock/content-type "application/json; charset=UTF-8"))]
    (base/send-request-with-tx request)))

(defn- post-exam-date [exam-date-entry]
  (let [request (-> (mock/request :post (str routing/organizer-api-root "/1.2.3.4/exam-date") exam-date-entry)
                    (mock/content-type "application/json; charset=UTF-8"))]
    (base/send-request-with-tx request)))

(defn- delete-exam-date [id]
  (let [request (-> (mock/request :delete (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id))))]
    (base/send-request-with-tx request)))

(deftest get-exam-dates-test
  (testing "can get exam dates"
    (let [request (mock/request :get (str routing/organizer-api-root "/1.2.3.4/exam-date"))
          response (base/send-request-with-tx request)]
      (testing "should return 200"
        (is (= (:status response) 200))))))

(deftest exam-date-new-date-test

  (base/insert-organizer "'1.2.3.4'")

  (testing "creating a new exam date adds a new exam date entity to the system"
    (let [new-dates {:exam_date "2041-06-07"}
          exam-date-entry (create-exam-date-entry new-dates)
          response (post-exam-date exam-date-entry)
          response-body (base/body-as-json response)
          date-id (get-in response-body ["id"])]
      (is (= (:status response) 200))
      (is (= date-id (get-exam-date-id-by-date (:exam_date new-dates))))))

  (testing "cannot create a new exam date if same date already exists"
    (let [new-dates {:exam_date "2041-06-07"}
          exam-date-entry (create-exam-date-entry new-dates)
          response (post-exam-date exam-date-entry)
          response-body (base/body-as-json response)]
      (is (= (get-success-status response-body) false))
      (is (= (:status response) 409))))

  (testing "cannot create a new exam date when registration end date is before registration start date"
    (let [new-dates {:exam_date "2041-06-08"
                     :registration_start_date "2041-03-07"
                     :registration_end_date "2041-03-01"}
          exam-date-entry (create-exam-date-entry new-dates)
          response (post-exam-date exam-date-entry)
          response-body (base/body-as-json response)]
      (is (= (get-success-status response-body) false))
      (is (= (:status response) 409))))

  (testing "cannot create a new exam date when registration end date is not before exam date"
    (let [new-dates {:exam_date "2041-06-09"
                     :registration_start_date "2041-06-01"
                     :registration_end_date "2041-06-11"}
          exam-date-entry (create-exam-date-entry new-dates)
          response (post-exam-date exam-date-entry)
          response-body (base/body-as-json response)]
      (is (= (get-success-status response-body) false))
      (is (= (:status response) 409)))))

(deftest exam-date-delete-date-test
  (base/insert-organizer "'1.2.3.4'")

  (let [new-dates {:exam_date "2040-06-07"}
        exam-date-entry (create-exam-date-entry new-dates)]
    (post-exam-date exam-date-entry))

  (testing "delete sets deleted_at timestamp to exam date"
    (let [date-id (get-exam-date-id-by-date "2040-06-07")
          response (delete-exam-date date-id)]
      (is (not (nil? (:deleted_at (base/select-one (base/select-exam-date date-id))))))
      (is (= (:status response) 200))))

  (testing "deleted exam date is not returned by get"
    (let [date-id (get-exam-date-id-by-date "2040-06-07")
          response (get-exam-date date-id)
          response-body (base/body-as-json response)]
      (is (= (get-success-status response-body) false))
      (is (= (:status response) 404))))

  (testing "cannot delete an exam date that has exam sessions assigned to it"
    (base/insert-custom-exam-date "2039-10-01" "2039-10-01" "2039-10-30")
    (let [date-id (get-exam-date-id-by-date "2039-10-01")
          insert-session (base/insert-exam-session date-id "'1.2.3.4'" 5)
          response (delete-exam-date date-id)
          response-body (base/body-as-json response)]
      (is (= (get-success-status response-body) false))
      (is (= (:status response) 409)))))

(deftest exam-date-language-test

  (base/insert-organizer "'1.2.3.4'")

  (let [post-exam-date-languages (fn [id exam-date-languages]
                                   (let [request (-> (mock/request :post (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id "/languages")) exam-date-languages)
                                                     (mock/content-type "application/json; charset=UTF-8"))]
                                     (base/send-request-with-tx request)))]
    (testing "can add languages to the exam date on exam date creation"
      (let [languages (j/read-value (slurp "test/resources/languages.json"))
            new-dates {:exam_date "2040-06-15"
                       :languages languages}
            exam-date-entry (create-exam-date-entry new-dates)
            response (post-exam-date exam-date-entry)
            response-body (base/body-as-json response)
            date-id (get-in response-body ["id"])
            exam-date (base/select-one (base/select-exam-date-id-by-date (:exam_date new-dates)))]
        (is (= (:status response) 200))
        (is (= date-id (:id exam-date)))
        (is (= 2 (count (base/select (base/select-exam-date-languages-by-date-id date-id)))))))

    (testing "languages are deleted on exam date delete"
      (let [exam-date-id (get-exam-date-id-by-date "2040-06-15")
            response (delete-exam-date exam-date-id)]
        (is (= (:status response) 200))
        (is (not (nil? (:deleted_at (base/select-one (base/select-exam-date exam-date-id))))))
        (is (= 0 (count (base/select (base/select-exam-date-languages-by-date-id exam-date-id)))))))

    (testing "can add a language to an existing exam date"
      (base/insert-custom-exam-date "2039-10-12" "2039-10-01" "2039-10-30")
      (let [languages (slurp "test/resources/languages.json")
            exam-date-id (get-exam-date-id-by-date "2039-10-12")
            response (post-exam-date-languages exam-date-id languages)]
        (is (= (:status response) 200))
        (is (= 2 (count (base/select (base/select-exam-date-languages-by-date-id exam-date-id)))))))

    (testing "cannot add a language-level pair to a exam date if it already exists"
      (let [languages (slurp "test/resources/languages.json")
            exam-date-id (get-exam-date-id-by-date "2039-10-12")
            response (post-exam-date-languages exam-date-id languages)
            response-body (base/body-as-json response)]
        (is (= (:status response) 409))
        (is (= (get-success-status response-body) false))
        (is (= 2 (count (base/select (base/select-exam-date-languages-by-date-id exam-date-id))))))))

  (let [delete-exam-date-languages (fn [id exam-date-languages]
                                     (let [request (-> (mock/request :delete (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id "/languages")) exam-date-languages)
                                                       (mock/content-type "application/json; charset=UTF-8"))]
                                       (base/send-request-with-tx request)))]
    (testing "can delete a language from an existing exam date"
      (let [language (slurp "test/resources/language_fin.json")
            exam-date-id (get-exam-date-id-by-date "2039-10-12")
            response (delete-exam-date-languages exam-date-id language)]
        (is (= (:status response) 200))
        (is (= 1 (count (base/select (base/select-exam-date-languages-by-date-id exam-date-id)))))))

    (testing "cannot delete a language from an exam date that has exam sessions assigned to it"
      (let [exam-date-id (get-exam-date-id-by-date "2039-10-12")
            language (slurp "test/resources/language_eng.json")
            insert-session (base/insert-exam-session exam-date-id "'1.2.3.4'" 5)
            response (delete-exam-date-languages exam-date-id language)
            response-body (base/body-as-json response)]
        (is (= (:status response) 409))
        (is (= (get-success-status response-body) false))
        (is (= 1 (count (base/select (base/select-exam-date-languages-by-date-id exam-date-id)))))))))

(deftest exam-date-post-admission-configure-test

  (base/insert-custom-exam-date "2042-06-01" "2042-01-01" "2042-01-30")
  (let [configure-post-admission (fn [id configuration]
                                   (let [request (-> (mock/request :post (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id "/post-admission")) configuration)
                                                     (mock/content-type "application/json; charset=UTF-8"))]
                                     (base/send-request-with-tx request)))
        exam-date-id (get-exam-date-id-by-date "2042-06-01")]

    (testing "can configure post admission for the exam date"
      (let [post-admission {:post_admission_start_date "2042-02-01"
                            :post_admission_end_date "2042-02-28"
                            :post_admission_enabled false}
            configuration (create-new-entry "{}" post-admission)
            response (configure-post-admission exam-date-id configuration)
            exam-date (base/select-one (base/select-exam-date exam-date-id))]
        (is (= (:status response) 200))
        (is (= (:post_admission_end_date exam-date) (:post_admission_end_date post-admission)))
        (is (= (:post_admission_start_date exam-date) (:post_admission_start_date post-admission)))
        (is (= (:post_admission_enabled exam-date) (:post_admission_enabled post-admission)))))

    (testing "cannot add a post admission start date that is before it's end date"
      (let [post-admission {:post_admission_start_date "2042-02-28"
                            :post_admission_end_date "2042-02-01"
                            :post_admission_enabled false}
            configuration (create-new-entry "{}" post-admission)
            response (configure-post-admission exam-date-id configuration)
            response-body (base/body-as-json response)]
        (is (= (:status response) 409))
        (is (= (get-success-status response-body) false))))

    (testing "cannot add a post admission start date that is before registration end"
      (let [post-admission {:post_admission_start_date "2042-01-28"
                            :post_admission_end_date "2042-02-28"
                            :post_admission_enabled false}
            configuration (create-new-entry "{}" post-admission)
            response (configure-post-admission exam-date-id configuration)
            response-body (base/body-as-json response)]
        (is (= (:status response) 409))
        (is (= (get-success-status response-body) false))))

    (testing "cannot add a post admission end date that is after exam date"
      (let [post-admission {:post_admission_start_date "2042-02-28"
                            :post_admission_end_date "2042-06-28"
                            :post_admission_enabled false}
            configuration (create-new-entry "{}" post-admission)
            response (configure-post-admission exam-date-id configuration)
            response-body (base/body-as-json response)]
        (is (= (:status response) 409))
        (is (= (get-success-status response-body) false)))))

  (base/insert-custom-exam-date "2042-12-01" "2042-06-01" "2042-06-30")
  (let [toggle-post-admission (fn [id status-endpoint]
                                (let [request (-> (mock/request :post (str routing/organizer-api-root (str "/1.2.3.4/exam-date/" id "/post-admission/" status-endpoint)))
                                                  (mock/content-type "appll.ö,_Bication/json; charset=UTF-8"))]
                                  (base/send-request-with-tx request)))
        configured-exam-date-id (get-exam-date-id-by-date "2042-06-01")
        new-exam-date-id (get-exam-date-id-by-date "2042-12-01")]

    (testing "can enable post admission"
      (let [response (toggle-post-admission configured-exam-date-id "enable")
            exam-date (base/select-one (base/select-exam-date configured-exam-date-id))]
        (is (= (:status response) 200))
        (is (= (:post_admission_enabled exam-date) true))))

    (testing "can disable post admission"
      (let [response (toggle-post-admission configured-exam-date-id "disable")
            exam-date (base/select-one (base/select-exam-date configured-exam-date-id))]
        (is (= (:status response) 200))
        (is (= (:post_admission_enabled exam-date) false))))

    (testing "cannot toggle post admission if dates have not been configured"
      (let [response (toggle-post-admission new-exam-date-id "enable")
            exam-date (base/select-one (base/select-exam-date new-exam-date-id))]
        (is (= (:status response) 409))
        (is (= (:post_admission_enabled exam-date) false))))))
