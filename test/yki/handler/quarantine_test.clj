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

(def session-virkailija-oid "1.2.3.4")

(defn read-json-body [response]
  (some-> (:body response)
          (slurp)
          (json/read-str :key-fn keyword)))

(defn create-handlers [port]
  (let [access-log         (base/access-log)
        db                 (base/db)
        url-helper         (base/create-url-helper (str "localhost" port))
        quarantine-handler (ig/init-key
                             :yki.handler/quarantine
                             {:access-log access-log
                              :auth       (base/no-auth-fake-session-oid-middleware session-virkailija-oid)
                              :db         db
                              :url-helper url-helper})]
    (core/routes quarantine-handler)))

(deftest get-quarantines-test
  (base/insert-quarantine base/quarantine-form)
  (let [request  (mock/request :get routing/quarantine-api-root)
        response (base/send-request-with-tx request)]
    (testing "get quarantines endpoint should return 200"
      (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
      (is (= (:status response) 200))
      (is (= (count (:quarantines (read-json-body response))) 1)))))

(defn- request-with-json-body [session route method data]
  (let [{response :response} (peridot/request
                               session
                               route
                               :request-method method
                               :body (some-> data (json/write-str))
                               :content-type "application/json")
        body (read-json-body response)]
    {:status (:status response)
     :body   body}))

(deftest get-quarantine-matches-test
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-quarantine base/quarantine-form)
  (with-routes!
    {}
    (let [handler     (create-handlers port)
          session     (peridot/session handler)
          get-matches #(request-with-json-body session (str routing/quarantine-api-root "/matches") :get nil)
          response    (get-matches)]
      (testing "get quarantine endpoint should return 200"
        (is (= (:status response) 200)))
      (testing "initially no matches should be returned"
        (is (= (:body response) {:quarantines []})))
      (testing "match if registration birth date matches and exam date is between quarantine start and end dates"
        (base/update-exam-date! 1 "2030-01-01")
        (base/update-registration-form! 1 "birthdate" (:birthdate base/quarantine-form))
        (is (= (-> (get-matches)
                   (:body)
                   (:quarantines)
                   (count))
               1)))
      (testing "only registrations in state COMPLETED or SUBMITTED can be matches"
        (doseq [state ["STARTED" "EXPIRED" "CANCELLED" "PAID_AND_CANCELLED"]]
          (base/update-registration-state! 1 state)
          (is (empty? (-> (get-matches)
                          (:body)
                          (:quarantines)))))
        (doseq [state ["SUBMITTED" "COMPLETED"]]
          (base/update-registration-state! 1 state)
          (is (= (-> (get-matches)
                     (:body)
                     (:quarantines)
                     (count))
                 1))))
      (testing "one quarantine can produce multiple matches"
        (base/update-registration-form! 2 "birthdate" (:birthdate base/quarantine-form))
        (is (= (-> (get-matches)
                   (:body)
                   (:quarantines)
                   (count))
               2)))
      (testing "multiple quarantines can match same registration"
        (base/insert-quarantine (dissoc base/quarantine-form :ssn))
        (is (= (->> (get-matches)
                    (:body)
                    (:quarantines)
                    (map #(select-keys % [:id :registration_id]))
                    (into #{}))
               #{{:id 1 :registration_id 1} {:id 1 :registration_id 2}
                 {:id 2 :registration_id 1} {:id 2 :registration_id 2}})))
      (testing "a reviewed quarantine+registration combination is not reported as a match"
        (doseq [[id registration-id quarantined] [[1 1 false]
                                                  [2 2 true]]]
          (request-with-json-body
            session
            (str/join "/" [routing/quarantine-api-root id "registration" registration-id "set"])
            :put
            {:is_quarantined quarantined}))
        (is (= (->> (get-matches)
                    (:body)
                    (:quarantines)
                    (map #(select-keys % [:id :registration_id]))
                    (into #{}))
               ; Note: only the below match should be returned,
               ; as confirming quarantine 2 for registration 2
               ; also cancels registration 2.
               #{{:id 2 :registration_id 1}})))
      (testing "exam language on registration and quarantine must be same for a match"
        (base/update-quarantine-language! 2 "swe")
        (is (empty? (->> (get-matches)
                         (:body)
                         (:quarantines))))))))

(deftest delete-quarantine-test
  (base/insert-quarantine base/quarantine-form)
  (let [delete! #(base/send-request-with-tx (mock/request :delete (str routing/quarantine-api-root "/" %)))]
    (testing "delete quarantine endpoint should return 200 and delete existing quarantine"
      (is (= (:status (delete! 1)) 200))
      (is (= (:count (base/select-one "SELECT COUNT(*) FROM quarantine")) 0)))
    (testing "delete endpoint should return 404 if quarantine with given id does not exist"
      (is (= (:status (delete! 999)) 404))
      (is (= (:status (delete! 1)) 404)))))

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
  (base/insert-quarantine base/quarantine-form)
  (with-routes!
    {}
    (let [handler                                    (create-handlers port)
          session                                    (peridot/session handler)
          set-registration-quarantine-state          (fn [registration-id quarantine-id quarantined?]
                                                       (request-with-json-body
                                                         session
                                                         (str/join "/" [routing/quarantine-api-root quarantine-id
                                                                        "registration"
                                                                        registration-id
                                                                        "set"])
                                                         :put
                                                         {:is_quarantined quarantined?}))
          select-quarantine-details-for-registration #(base/select-one
                                                        (str "SELECT r.id, r.state, qr.quarantine_id, qr.quarantined FROM registration r INNER JOIN quarantine_review qr ON r.id = qr.registration_id WHERE r.id = " % ";"))]
      (testing "set quarantine endpoint should return 200 and cancel registration"
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 1 1 true)))
        (is (= {:id 1 :state "CANCELLED" :quarantine_id 1 :quarantined true}
               (select-quarantine-details-for-registration 1)))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 4 1 true)))
        (is (= {:id 4 :state "PAID_AND_CANCELLED" :quarantine_id 1 :quarantined true}
               (select-quarantine-details-for-registration 4))))
      (testing "set quarantine endpoint should allow setting registration as not quarantined"
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 1 1 false)))
        (is (= {:id 1 :state "CANCELLED" :quarantine_id 1 :quarantined false}
               (select-quarantine-details-for-registration 1)))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 4 1 false)))
        (is (= {:id 4 :state "PAID_AND_CANCELLED" :quarantine_id 1 :quarantined false}
               (select-quarantine-details-for-registration 4)))))))
