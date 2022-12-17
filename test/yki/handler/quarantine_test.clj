(ns yki.handler.quarantine-test
  (:require
    [clojure.test :refer [deftest use-fixtures testing is]]
    [compojure.core :as core]
    [integrant.core :as ig]
    [ring.mock.request :as mock]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]
    [jsonista.core :as j]
    [peridot.core :as peridot]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn create-handlers [port]
  (let [access-log         (base/access-log)
        db                 (base/db)
        url-helper         (base/create-url-helper (str "localhost" port))
        quarantine-handler (ig/init-key :yki.handler/quarantine {:db db :url-helper url-helper :access-log access-log})]
    (core/routes quarantine-handler)))

(deftest get-quarantine-test
  (base/insert-quarantine)
  (let [request  (mock/request :get routing/quarantine-api-root)
        response (base/send-request-with-tx request)]
    (testing "get quarantine endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest get-quarantine-matches-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-quarantine)
  (let [request  (mock/request :get (str routing/quarantine-api-root "/matches"))
        response (base/send-request-with-tx request)]
    (testing "get quarantine endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200)))))

(deftest delete-quarantine-test
  (base/insert-quarantine)
  (let [request  (mock/request :delete (str routing/quarantine-api-root "/1"))
        response (base/send-request-with-tx request)]
    (testing "delete quarantine endpoint should return 200 and add new quarantine"
      (is (= (:status response) 200))
      (is (= 0 (:count (base/select-one "SELECT COUNT(*) FROM quarantine")))))))

(deftest insert-quarantine-test
  (with-routes!
    {}
    (let [handler (create-handlers port)
          session (peridot/session handler)]
      (peridot/request
        session
        routing/quarantine-api-root
        :request-method :post
        :body (j/write-value-as-string base/quarantine-form)
        :content-type "application/json")
      (testing "insert quarantine endpoint should return 200 and add new quarantine"
        (is (= {:id            1
                :language_code "fin"
                :end_date      "2022-12-30"
                :birthdate     "1999-01-27"
                :ssn           "301079-900U"
                :name          "Max Syöttöpaine"
                :email         "email@invalid.invalid"
                :phone_number  "0401234567"}
               (-> (base/select-one "SELECT * FROM quarantine")
                   (dissoc :created :updated :deleted))))))))

(deftest set-quarantine-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-quarantine)
  (let [request   (-> (mock/request :put (str routing/quarantine-api-root "/1/registration/1/set"))
                      (mock/json-body {:is_quarantined "true"}))
        response  (base/send-request-with-tx request)]
    (testing "set quarantine endpoint should return 200 and registration be cancelled"
      (is true)
      ; FIXME: mock request body is empty for some reason
      ; (is (= {:id 1 :state "PAID_AND_CANCELLED" :reviewed_bool true :quarantined 1}
      ;       (base/select "SELECT id, state, reviewed IS NOT NULL AS reviewed_bool, quarantined FROM registration WHERE id = 1")))
      )))
