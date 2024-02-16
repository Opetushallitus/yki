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
                             {:access-log     access-log
                              :auth           (base/no-auth-fake-session-oid-middleware session-virkailija-oid)
                              :db             db
                              :error-boundary (base/error-boundary)
                              :url-helper     url-helper})]
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
          get-matches (fn []
                        (-> (request-with-json-body session (str routing/quarantine-api-root "/matches") :get nil)
                            (:body)
                            (:quarantine_matches)))]
      (testing "get quarantine endpoint should return 200"
        (is (= (:status (request-with-json-body session (str routing/quarantine-api-root "/matches") :get nil)) 200)))
      (testing "initially no matches should be returned"
        (is (= (get-matches) [])))
      (testing "match if registration birth date matches and exam date is between quarantine start and end dates"
        (base/update-exam-date! 1 (:start_date base/quarantine-form))
        (base/update-registration-form! 1 "birthdate" (:birthdate base/quarantine-form))
        (is (= (-> (get-matches)
                   (count))
               1)))
      (testing "only registrations in state COMPLETED or SUBMITTED can be matches"
        (doseq [state ["STARTED" "EXPIRED" "CANCELLED" "PAID_AND_CANCELLED"]]
          (base/update-registration-state! 1 state)
          (is (empty? (get-matches))))
        (doseq [state ["SUBMITTED" "COMPLETED"]]
          (base/update-registration-state! 1 state)
          (is (= (-> (get-matches)
                     (count))
                 1))))
      (testing "one quarantine can produce multiple matches and ssn on registration form can be matched against quarantine birthdate as well"
        (base/update-registration-form! 2 "birthdate" nil)
        (base/update-registration-form! 2 "ssn" (:ssn base/quarantine-form))
        (is (= (-> (get-matches)
                   (count))
               2))
        (testing "missing birthdate on registration form is replaced by value converted from SSN"
          (is (= ["1999-01-27" "1999-01-27"]
                 (->> (get-matches)
                      (map (comp :birthdate :form)))))))
      (testing "multiple quarantines can match same registration"
        (base/insert-quarantine (-> base/quarantine-form
                                    (dissoc :ssn)
                                    (assoc :diary_number "OPH-2023-999")))
        (base/insert-quarantine (-> base/quarantine-form
                                    (assoc :ssn (:ssn base/registration-form))
                                    (assoc :diary_number "OPH-2023-1001")))
        (is (= (->> (get-matches)
                    (map #(select-keys % [:id :registration_id]))
                    (into #{}))
               #{; quarantine 1 matches registration 1 by birthdate
                 {:id 1 :registration_id 1}
                 ; quarantine 1 matches registration 2 by SSN
                 {:id 1 :registration_id 2}
                 ; quarantine 2 matches registration 1 by birthdate
                 {:id 2 :registration_id 1}
                 ; quarantine 3 matches registration 1 by SSN
                 {:id 3 :registration_id 1}
                 })))
      (testing "a reviewed quarantine+registration combination is no longer reported as a match"
        (doseq [[id registration-id quarantined] [[1 1 false]
                                                  [2 2 true]]]
          (request-with-json-body
            session
            (str/join "/" [routing/quarantine-api-root id "registration" registration-id "set"])
            :put
            {:is_quarantined quarantined}))
        (is (= (->> (get-matches)
                    (map #(select-keys % [:id :registration_id]))
                    (into #{}))
               ; Note: only the below matches should be returned,
               ; as confirming quarantine 2 for registration 2
               ; also cancels registration 2.
               #{{:id 2 :registration_id 1}
                 {:id 3 :registration_id 1}})))
      (testing "exam language on registration and quarantine must be same for a match"
        (base/update-quarantine-language! 2 "swe")
        (base/update-quarantine-language! 3 "sme")
        (is (empty? (get-matches))))
      (testing "if a quarantine is updated after it was reviewed, it can again be matched"
        (base/update-quarantine-language! 1 "fin")
        (is (= (->> (get-matches)
                    (map #(select-keys % [:id :registration_id]))
                    (into #{}))
               #{{:id 1 :registration_id 1}}))))))

(deftest delete-quarantine-test
  (base/insert-quarantine base/quarantine-form)
  (let [delete! #(base/send-request-with-tx (mock/request :delete (str routing/quarantine-api-root "/" %)))]
    (testing "delete quarantine endpoint should return 200 and mark existing quarantine deleted"
      (is (= (:status (delete! 1)) 200))
      (is (= (:count (base/select-one "SELECT COUNT(*) FROM quarantine WHERE deleted_at IS NULL")) 0))
      (is (= (:count (base/select-one "SELECT COUNT(*) FROM quarantine WHERE deleted_at IS NOT NULL")) 1)))
    (testing "delete endpoint should return 404 if quarantine with given id does not exist or is already deleted"
      (is (= (:status (delete! 999)) 404))
      (is (= (:status (delete! 1)) 404)))))

(deftest insert-quarantine-test
  (with-routes!
    {}
    (let [handler            (create-handlers port)
          session            (peridot/session handler)
          insert-quarantine! #(request-with-json-body
                                session
                                routing/quarantine-api-root
                                :post
                                %)]
      (testing "insert quarantine endpoint should return 200 and add new quarantine"
        (is (= {:status 200
                :body   {:success true}} (insert-quarantine! base/quarantine-form)))
        (is (= (assoc base/quarantine-form :id 1)
               (-> (base/select-one "SELECT * FROM quarantine")
                   (dissoc :created :updated :deleted_at)))))
      (testing "if ssn is given"
        (testing "missing birthdate should be inferred from ssn"
          (is (= {:status 200
                  :body   {:success true}}
                 (insert-quarantine! (-> base/quarantine-form
                                         (dissoc :birthdate)
                                         (update :diary_number #(str % (random-uuid)))))))
          (is (= (:birthdate base/quarantine-form)
                 (:birthdate (base/select-one "SELECT * FROM quarantine WHERE id=2;")))))
        (testing "mismatching birthdate should result in an error"
          (is (= 400
                 (:status (insert-quarantine! (-> base/quarantine-form
                                                  (assoc :birthdate "1999-01-28"))))))
          (is (= {:count 2}
                 (base/select-one "SELECT COUNT(*) FROM quarantine;"))))))))

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
                                                        (str "SELECT r.id, r.state, qr.quarantine_id, qr.quarantined FROM registration r INNER JOIN quarantine_review qr ON r.id = qr.registration_id WHERE r.id = " % ";"))
          update-registration-exam-date!             (fn [registration-id new-exam-date]
                                                       (base/execute! (str "UPDATE exam_date SET exam_date='" new-exam-date "' WHERE id IN (SELECT ed.id FROM exam_date ed INNER JOIN exam_session es ON ed.id = es.exam_date_id INNER JOIN registration r ON es.id = r.exam_session_id WHERE r.id=" registration-id ");")))]
      (testing "set quarantine endpoint should return 200"
        (testing "confirming a quarantine must not cancel registration if the exam date is in the past"
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 1 1 true)))
          (is (= {:id 1 :state "SUBMITTED" :quarantine_id 1 :quarantined true}
                 (select-quarantine-details-for-registration 1))))
        ; Move exam to a future date. Registrations 1 to 5 share the same exam date, so the following call suffices
        ; to ensure all registrations in this test can be cancelled going forward by the "confirm quarantine" functionality.
        (update-registration-exam-date! 1 "2035-12-31")
        (testing "confirming a quarantine before the exam date must cancel the corresponding registration"
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 1 1 true)))
          (is (= {:id 1 :state "CANCELLED" :quarantine_id 1 :quarantined true}
                 (select-quarantine-details-for-registration 1))))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 2 1 true)))
        (is (= {:id 2 :state "CANCELLED" :quarantine_id 1 :quarantined true}
               (select-quarantine-details-for-registration 2)))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 4 1 true)))
        (is (= {:id 4 :state "PAID_AND_CANCELLED" :quarantine_id 1 :quarantined true}
               (select-quarantine-details-for-registration 4))))
      (testing "set quarantine endpoint should allow setting registration as not quarantined"
        (testing "already cancelled registrations stay cancelled"
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 1 1 false)))
          (is (= {:id 1 :state "CANCELLED" :quarantine_id 1 :quarantined false}
                 (select-quarantine-details-for-registration 1)))
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 4 1 false)))
          (is (= {:id 4 :state "PAID_AND_CANCELLED" :quarantine_id 1 :quarantined false}
                 (select-quarantine-details-for-registration 4))))
        (testing "submitted or completed registrations stay as such"
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 3 1 false)))
          (is (= {:id 3 :state "SUBMITTED" :quarantine_id 1 :quarantined false}
                 (select-quarantine-details-for-registration 3)))
          (is (= {:body   {:success true}
                  :status 200}
                 (set-registration-quarantine-state 5 1 false)))
          (is (= {:id 5 :state "COMPLETED" :quarantine_id 1 :quarantined false}
                 (select-quarantine-details-for-registration 5)))))
      (testing "quarantine decisions can be fetched from /reviews"
        (let [{:keys [body status]} (request-with-json-body session (str routing/quarantine-api-root "/reviews") :get {})
              reviews (:reviews body)]
          (is (= 200 status))
          (is (= 5 (count reviews)))
          (is (= #{[1 1 false]
                   [1 2 true]
                   [1 3 false]
                   [1 4 false]
                   [1 5 false]}
                 (->> reviews
                      (map (juxt :quarantine_id :registration_id :is_quarantined))
                      (into #{}))))))
      (testing "once quarantine is deleted, it can no longer be reviewed"
        (base/insert-quarantine (-> base/quarantine-form
                                    (dissoc :ssn)
                                    (assoc :diary_number "OPH-99999-2023")))
        (is (= {:body   {:success true}
                :status 200}
               (set-registration-quarantine-state 6 2 false)))
        (is (= {:id 6 :state "COMPLETED" :quarantine_id 2 :quarantined false}
               (select-quarantine-details-for-registration 6)))
        (request-with-json-body session (str routing/quarantine-api-root "/" 2) :delete nil)
        (is (= 400 (:status (set-registration-quarantine-state 6 2 true))))))))
