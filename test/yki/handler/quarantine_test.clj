(ns yki.handler.quarantine-test
  (:require
    [clojure.test :refer [deftest use-fixtures testing is]]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [compojure.core :as core]
    [integrant.core :as ig]
    [peridot.core :as peridot]
    [ring.mock.request :as mock]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

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
    (testing "delete quarantine endpoint should return 200 and delete existing quarantine"
      (is (= (:status response) 200))
      (is (= 0 (:count (base/select-one "SELECT COUNT(*) FROM quarantine")))))))

(defn- request-with-json-body [session route method data]
  (let [{response :response} (peridot/request
                               session
                               route
                               :request-method method
                               :body (json/write-str data)
                               :content-type "application/json")
        body (-> (:body response)
                 (slurp)
                 (json/read-str :key-fn keyword))]
    {:status (:status response)
     :body   body}))

(deftest insert-quarantine-test
  (with-routes!
    {}
    (let [handler  (create-handlers port)
          session  (peridot/session handler)
          response (request-with-json-body
                     session
                     routing/quarantine-api-root
                     :post
                     base/quarantine-form)]
      (testing "insert quarantine endpoint should return 200 and add new quarantine"
        (is (= {:status 200
                :body   {:success true}} response))
        (is (= (assoc base/quarantine-form :id 1)
               (-> (base/select-one "SELECT * FROM quarantine")
                   (dissoc :created :updated :deleted))))))))

(deftest set-quarantine-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-registrations "COMPLETED")
  (base/insert-quarantine)
  (with-routes!
    {}
    (let [handler                           (create-handlers port)
          session                           (peridot/session handler)
          set-registration-quarantine-state (fn [registration-id quarantine-id quarantined?]
                                              (request-with-json-body
                                                session
                                                (str/join "/" [routing/quarantine-api-root quarantine-id
                                                               "registration"
                                                               registration-id
                                                               "set"])
                                                :put
                                                {:is_quarantined quarantined?}))
          select-quarantine-details-for-registration #(base/select-one
                                                        (str "SELECT id, state, reviewed IS NOT NULL AS reviewed_bool, quarantine_id FROM registration WHERE id = " % ";"))]
      (testing "set quarantine endpoint should return 200 and cancel registration"
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 1 1 true)))
        (is (= {:id 1 :state "CANCELLED" :reviewed_bool true :quarantine_id 1}
               (select-quarantine-details-for-registration 1)))
        (is (= {:body {:success true}
                :status 200}
               (set-registration-quarantine-state 4 1 true)))
        (is (= {:id 4 :state "PAID_AND_CANCELLED" :reviewed_bool true :quarantine_id 1}
               (select-quarantine-details-for-registration 4))))
      (testing "set quarantine endpoint should allow setting registration as not quarantined"
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 1 1 false)))
        (is (= {:id 1 :state "SUBMITTED" :reviewed_bool true :quarantine_id nil}
               (select-quarantine-details-for-registration 1)))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 4 1 false)))
        (is (= {:id 4 :state "PAID_AND_CANCELLED" :reviewed_bool true :quarantine_id nil}
               (select-quarantine-details-for-registration 4)))))))
