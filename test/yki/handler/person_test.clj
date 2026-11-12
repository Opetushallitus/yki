(ns yki.handler.person-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [compojure.core :refer [routes]]
    [integrant.core :as ig]
    [peridot.core :as peridot]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.boundary.registration-db :as registration-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(deftest person-authentication-test
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          auth           (base/auth url-helper)
          payment-helper (base/create-examination-payment-helper db url-helper)
          handler        (base/person-handler auth url-helper payment-helper)
          routes         (routes handler)
          session        (peridot/session routes)]
      (testing "accessing endpoints under person requires authentication"
        (let [response (-> session
                           (peridot/request routing/person-api-root :request-method :get))]
          (is (= 401 (get-in response [:response :status]))))))))

(defn read-response-json [response]
  (-> response
      (get-in [:response :body])
      (slurp)
      (json/read-str :key-fn keyword)))

(def person-fields [:oid :first_name :last_name :email :phone_number :street_address :post_office :zip :country_code])

(deftest person-details-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "COMPLETED")
  (base/insert-unpaid-expired-registration)
  ; Make all registrations belong to person with oid 5.4.3.2.1
  (base/execute! "UPDATE registration SET person_oid='5.4.3.2.1'")
  ; Make exam session 1 recent enough so that registrations are returned through person APIs
  (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 month' WHERE id=1") ;
  (with-routes!
    {{:path "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" :method :get} {:status 200 :content-type "application/json"
                                                                                                  :body   (slurp "test/resources/maatjavaltiot2_246.json")}

     {:path "/oph/osallistuja/5.4.3.2.1" :method :put}                                           {:status 200}}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)]
      (testing "person details endpoint returns 404 if there is no person linked to user identity"
        (let [fake-auth (ig/init-key :yki.middleware.no-auth/with-fake-session
                                     {:identity    {:oid "OID-not-found"}
                                      :auth-method "SUOMIFI"})
              handler   (base/person-handler fake-auth url-helper payment-helper)
              routes    (routes handler)
              session   (peridot/session routes)
              response  (-> session
                            (peridot/request routing/person-api-root :request-method :get))]
          (is (= 404 (get-in response [:response :status])))))
      (testing "person details are returned if person corresponding to user identity is found from DB"
        (let [person (-> base/registration-form
                         (assoc :oid "5.4.3.2.1")
                         (select-keys person-fields))]
          (testing "all registrations for person are returned when user is strongly authenticated"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid (:oid person)}
                                              :auth-method "SUOMIFI"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= #{1 2 3 4} (->> response-data
                                     :registrations
                                     (map :id)
                                     (into #{}))))))
          (testing "registrations for exam sessions over a year ago are not returned"
            (let [fake-auth (ig/init-key :yki.middleware.no-auth/with-fake-session
                                         {:identity    {:oid (:oid person)}
                                          :auth-method "SUOMIFI"})
                  handler   (base/person-handler fake-auth url-helper payment-helper)
                  routes    (routes handler)
                  session   (peridot/session routes)]
              ; Move exam session date to just over a year ago -> no registrations are to be returned
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 year 1 day' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= #{} (->> response-data
                                :registrations
                                (map :id)
                                (into #{})))))
              ; Move exam session date to exactly a year ago -> registrations should again be returned
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 year' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= #{1 2 3 4} (->> response-data
                                       :registrations
                                       (map :id)
                                       (into #{})))))
              ; Access to registrations to future exam sessions should not be restricted
              (base/execute! "UPDATE exam_date SET exam_date = current_date + interval '10 years' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= #{1 2 3 4} (->> response-data
                                       :registrations
                                       (map :id)
                                       (into #{})))))
              ; Finally, reset exam date to a month ago
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 month' WHERE id=1")))
          (testing "weakly authenticated user only receives details related to registration linked with login code"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid             (:oid person)
                                                            :registration-id 1}
                                              :auth-method "EMAIL"
                                              :auth-target "PERSON"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= #{1} (->> response-data
                               :registrations
                               (map :id)
                               (into #{}))))))
          (testing "no registrations are returned for weakly authenticated user in case registration linked with login code is not found"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid             (:oid person)
                                                            :registration-id 999}
                                              :auth-method "EMAIL"
                                              :auth-target "PERSON"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= #{} (->> response-data
                              :registrations
                              (map :id)
                              (into #{}))))))
          (testing "user can modify their contact details"
            (let [fake-auth              (ig/init-key :yki.middleware.no-auth/with-fake-session
                                                      {:identity    {:oid (:oid person)}
                                                       :auth-method "SUOMIFI"})
                  handler                (base/person-handler fake-auth url-helper payment-helper)
                  routes                 (routes handler)
                  session                (peridot/session routes)
                  new-contact-details    {:email          "new_email@test.invalid"
                                          :phone_number   "+991231231122"
                                          :street_address "Toisiotie 9"
                                          :post_office    "Kajaani"
                                          :zip            "80100"
                                          :country_code   "246"}
                  post-response          (-> session
                                             (peridot/request
                                               routing/person-api-root
                                               :request-method :post
                                               :content-type "application/json"
                                               :body (json/write-str
                                                       (assoc new-contact-details
                                                              :first_name "NOT UPDATED"
                                                              :last_name "NOT UPDATED"
                                                              :oid "NOT UPDATED"))))
                  post-response-data     (read-response-json post-response)
                  get-response           (-> session
                                             (peridot/request routing/person-api-root :request-method :get))
                  get-response-data      (read-response-json get-response)
                  persons-sync-handler   (ig/init-key :yki.job.scheduled-tasks/persons-sync-handler
                                                      {:db                     db
                                                       :url-helper             url-helper
                                                       :basic-auth             {:user     "user"
                                                                                :password "pass"}
                                                       :disabled               false
                                                       :retry-duration-in-days 1})
                  _                      (persons-sync-handler)
                  solki-request          (->> @(:routes server)
                                              (filter #(str/starts-with? (:path (:request-spec %)) "/oph/"))
                                              (first)
                                              (:recordings)
                                              (first))
                  expected-solki-payload {:sukunimi         (:last_name person)
                                          :etunimet         (:first_name person)
                                          :sahkoposti       (:email new-contact-details)
                                          :katuosoite       (:street_address new-contact-details)
                                          :postitoimipaikka (:post_office new-contact-details)
                                          :postinumero      (:zip new-contact-details)
                                          :kansalaisuus     "FIN"
                                          :sukupuoli        "M"
                                          :maa              "FIN"}]
              (is (= 200 (get-in post-response [:response :status])))
              (is (= {:success true} post-response-data))
              (is (= 200 (get-in get-response [:response :status])))
              (is (= (merge person new-contact-details) (dissoc get-response-data :registrations)))
              (is (= (get-in solki-request [:request :headers :authorization]) "Basic dXNlcjpwYXNz"))
              (is (= expected-solki-payload (-> solki-request
                                                (get-in [:request :body "content"])
                                                (json/read-str :key-fn keyword)))))))))))

(deftest person-registrations-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "SUBMITTED")
  (base/insert-unpaid-expired-registration)
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)
          oid-1          "5.4.3.2.1"
          oid-2          "5.4.3.2.2"]
      ; oid-1 should own registrations 1 and 2
      (base/execute! (str "UPDATE registration SET person_oid='" oid-1 "' WHERE id IN (1,2)"))
      ; oid-2 should own registrations 3 and 4
      ; update also registration 3 kind to 'ADMISSION' (from 'POST_ADMISSION') to ensure its details can be got from the /confirm endpoint
      (base/execute! (str "UPDATE registration SET kind='ADMISSION', person_oid='" oid-2 "' WHERE id IN (3,4)"))
      (testing "strongly authenticated person can view and act on all their registrations"
        (testing "oid-1 can query for confirmation details of registrations 1 and 2"
          (let [fake-auth          (ig/init-key :yki.middleware.no-auth/with-fake-session
                                                {:identity    {:oid oid-1}
                                                 :auth-method "SUOMIFI"})
                handler            (base/person-handler fake-auth url-helper payment-helper)
                routes             (routes handler)
                session            (peridot/session routes)
                confirm-response-1 (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 1 "/confirm") :request-method :get))
                response-data-1    (read-response-json confirm-response-1)
                confirm-response-2 (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 2 "/confirm") :request-method :get))
                response-data-2    (read-response-json confirm-response-2)
                unauthorized-3     (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 3 "/confirm") :request-method :get))]
            (is (= 200 (get-in confirm-response-1 [:response :status])))
            (is (= 1 (:id response-data-1)))
            (is (= 200 (get-in confirm-response-2 [:response :status])))
            (is (= 2 (:id response-data-2)))
            (is (= 404 (get-in unauthorized-3 [:response :status])))
            (is (= nil (get-in unauthorized-3 [:response :body])))))
        (testing "oid-2 can query for confirmation details of registration 3"
          (let [fake-auth          (ig/init-key :yki.middleware.no-auth/with-fake-session
                                                {:identity    {:oid oid-2}
                                                 :auth-method "SUOMIFI"})
                handler            (base/person-handler fake-auth url-helper payment-helper)
                routes             (routes handler)
                session            (peridot/session routes)
                unauthorized-1     (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 1 "/confirm") :request-method :get))
                unauthorized-2     (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 2 "/confirm") :request-method :get))
                confirm-response-3 (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 3 "/confirm") :request-method :get))
                response-data-3    (read-response-json confirm-response-3)
                expired-4          (-> session
                                       (peridot/request (str routing/person-api-root routing/registration-uri "/" 4 "/confirm") :request-method :get))]
            (is (= 404 (get-in unauthorized-1 [:response :status])))
            (is (= nil (get-in unauthorized-1 [:response :body])))
            (is (= 404 (get-in unauthorized-2 [:response :status])))
            (is (= nil (get-in unauthorized-2 [:response :body])))
            (is (= 200 (get-in confirm-response-3 [:response :status])))
            (is (= 3 (:id response-data-3)))
            (is (= 404 (get-in expired-4 [:response :status])))
            (is (= nil (get-in expired-4 [:response :body]))))))
      (testing "weakly authenticated user can only access data related to registration linked with login code"
        (let [fake-auth          (ig/init-key :yki.middleware.no-auth/with-fake-session
                                              {:identity    {:oid             oid-1
                                                             :registration-id 1}
                                               :auth-method "EMAIL"
                                               :auth-target "PERSON"})
              handler            (base/person-handler fake-auth url-helper payment-helper)
              routes             (routes handler)
              session            (peridot/session routes)
              confirm-response-1 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 1 "/confirm") :request-method :get))
              response-data-1    (read-response-json confirm-response-1)
              confirm-response-2 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 2 "/confirm") :request-method :get))]
          (is (= 200 (get-in confirm-response-1 [:response :status])))
          (is (= 1 (:id response-data-1)))
          (is (= 401 (get-in confirm-response-2 [:response :status])))
          (is (= nil (get-in confirm-response-2 [:response :body])))))
      (testing "user can cancel their own registrations"
        (let [fake-auth              (ig/init-key :yki.middleware.no-auth/with-fake-session
                                                  {:identity    {:oid oid-1}
                                                   :auth-method "SUOMIFI"})
              handler                (base/person-handler fake-auth url-helper payment-helper)
              routes                 (routes handler)
              session                (peridot/session routes)
              cancel!                (fn [registration-id]
                                       (-> session
                                           (peridot/request (str routing/person-api-root routing/registration-uri "/" registration-id "?lang=fi") :request-method :delete)))
              get-registration-state (fn [registration-id]
                                       (-> (str "SELECT state FROM registration WHERE id=" registration-id)
                                           (base/select-one)
                                           (:state)))]
          ; Modify registration states: 1 -> SUBMITTED, 2 -> COMPLETED
          (base/execute! "UPDATE registration SET state='COMPLETED' WHERE id=2")
          (testing "cancellation is no longer possible if exam date is in the past"
            ; TODO Test that cancellation succeeds if tried at 7:59am, but not if tried at 8:01am on day of exam
            (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 day'")
            (let [registration->expected-state {1 "SUBMITTED"
                                                2 "COMPLETED"
                                                3 "SUBMITTED"
                                                4 "EXPIRED"}]
              (doseq [[id state] registration->expected-state]
                (let [cancel-response (cancel! id)
                      response-data   (read-response-json cancel-response)]
                  (is (= 200 (get-in cancel-response [:response :status])))
                  (is (= {:success false} response-data))
                  (is (= state
                         (get-registration-state id)))))))
          (testing "cancellation is possible up to 8am on the day of the exam"
            ; TODO Test that cancellation succeeds if tried at 7:59am, but not if tried at 8:01am on day of exam
            (base/execute! "UPDATE exam_date SET exam_date = current_date + interval '1 day'")
            ; User can cancel their own registrations
            (let [registration->expected-state {1 "CANCELLED"
                                                2 "PAID_AND_CANCELLED"}]
              (doseq [[id state] registration->expected-state]
                (let [cancel-response (cancel! id)
                      response-data   (read-response-json cancel-response)]
                  (is (= 200 (get-in cancel-response [:response :status])))
                  (is (= {:success true} response-data))
                  (is (= state
                         (get-registration-state id))))))
            ; User cannot cancel others' registrations
            (let [registration->expected-state {3 "SUBMITTED"
                                                4 "EXPIRED"}]
              (doseq [[id state] registration->expected-state]
                (let [cancel-response (cancel! id)
                      response-data   (read-response-json cancel-response)]
                  (is (= 200 (get-in cancel-response [:response :status])))
                  (is (= {:success false} response-data))
                  (is (= state
                         (get-registration-state id))))))))))))

(deftest person-unfinished-registrations-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "SUBMITTED")
  (base/execute! "UPDATE registration SET person_oid = '5.4.3.2.1'")
  (base/execute! "UPDATE registration SET state = 'STARTED', kind = 'QUEUE', form = NULL,
                 started_at = current_timestamp - interval '31 minutes' WHERE id = 1")
  (base/execute! "UPDATE registration SET state = 'CANCELLED', form = NULL WHERE id = 3")
  (base/execute! "UPDATE exam_date SET exam_date = current_date + 7 WHERE id = 1")
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)
          fake-auth      (ig/init-key :yki.middleware.no-auth/with-fake-session
                                      {:identity    {:oid "5.4.3.2.1"}
                                       :auth-method "SUOMIFI"})
          handler        (base/person-handler fake-auth url-helper payment-helper)
          session        (peridot/session (routes handler))
          registrations  (fn []
                           (let [response (peridot/request session routing/person-api-root :request-method :get)]
                             (is (= 200 (get-in response [:response :status])))
                             (:registrations (read-response-json response))))]
      (testing "unfinished and abandoned cancelled attempts are returned"
        (is (= #{{:id 1 :state "STARTED" :queue_ended false}
                 {:id 2 :state "SUBMITTED" :queue_ended false}
                 {:id 3 :state "CANCELLED" :queue_ended false}}
               (set (map #(select-keys % [:id :state :queue_ended]) (registrations))))))
      (testing "an abandoned expired attempt remains visible without a queue-ended reason"
        (registration-db/update-started-registrations-to-expired! db)
        (is (= "EXPIRED" (:state (base/select-one "SELECT state FROM registration WHERE id = 1"))))
        (base/execute! "UPDATE exam_date SET exam_date = current_date + 3 WHERE id = 1")
        (is (= #{{:id 1 :state "EXPIRED" :queue_ended false}
                 {:id 2 :state "SUBMITTED" :queue_ended false}
                 {:id 3 :state "CANCELLED" :queue_ended false}}
               (set (map #(select-keys % [:id :state :queue_ended]) (registrations))))))
      (testing "submitted registrations remain visible in all subsequent states"
        (doseq [state ["SUBMITTED" "COMPLETED" "EXPIRED" "CANCELLED" "PAID_AND_CANCELLED"]]
          (base/execute! (str "UPDATE registration SET state = '" state "' WHERE id = 2"))
          (is (= [{:id 2 :state state}]
                 (mapv #(select-keys % [:id :state]) (filter #(= 2 (:id %)) (registrations))))))))))

(deftest person-expired-queue-registrations-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "SUBMITTED")
  (base/execute! "UPDATE registration SET kind = 'QUEUE' WHERE id = 2")
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)
          fake-auth      (ig/init-key :yki.middleware.no-auth/with-fake-session
                                      {:identity    {:oid "5.4.3.2.1"}
                                       :auth-method "SUOMIFI"})
          handler        (base/person-handler fake-auth url-helper payment-helper)
          session        (peridot/session (routes handler))]
      (doseq [partial-exam-type ["ALL_PARTS" "READ" "LISTEN" "SPEAK" "WRITE"]]
        (testing partial-exam-type
          (base/execute! (str "UPDATE registration SET state = 'SUBMITTED', partial_exam_type = '"
                              partial-exam-type "' WHERE id = 2"))
          (doseq [[exam-days expected-state] [[7 "SUBMITTED"] [6 "EXPIRED"] [3 "EXPIRED"]]]
            (base/execute! (str "UPDATE exam_date SET exam_date = current_date + " exam-days
                                ", registration_start_date = current_date - 20,
                                 registration_end_date = current_date - 10 WHERE id = 1"))
            (registration-db/expire-queued-registrations-after-exam-date! db)
            (let [response (peridot/request session routing/person-api-root :request-method :get)]
              (is (= 200 (get-in response [:response :status])))
              (is (= [{:id 2 :kind "QUEUE" :state expected-state :partial_exam_type partial-exam-type :queue_ended (= expected-state "EXPIRED")}]
                     (mapv #(select-keys % [:id :kind :state :partial_exam_type :queue_ended])
                           (:registrations (read-response-json response))))))))))))

(deftest person-queue-ended-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "SUBMITTED")
  (base/execute! "UPDATE registration SET kind = 'QUEUE' WHERE id = 2")
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)
          fake-auth      (ig/init-key :yki.middleware.no-auth/with-fake-session
                                      {:identity    {:oid "5.4.3.2.1"}
                                       :auth-method "SUOMIFI"})
          handler        (base/person-handler fake-auth url-helper payment-helper)
          session        (peridot/session (routes handler))]
      (doseq [[description exam-days end-days kind state expected]
              [["seven days before exam" 7 -10 "QUEUE" "SUBMITTED" false]
               ["six days before exam, before scheduler" 6 -10 "QUEUE" "SUBMITTED" true]
               ["registration still open" 6 1 "QUEUE" "SUBMITTED" false]
               ["registration has not opened" 6 4 "QUEUE" "SUBMITTED" false]
               ["scheduler already expired the queue" 3 -10 "QUEUE" "EXPIRED" true]
               ["scheduler expiry wins even if registration is still open" 3 1 "QUEUE" "EXPIRED" true]
               ["unfinished registration" 3 -10 "QUEUE" "STARTED" false]
               ["cancelled queue" 3 -10 "QUEUE" "CANCELLED" false]
               ["paid cancelled queue" 3 -10 "QUEUE" "PAID_AND_CANCELLED" false]
               ["confirmed registration" 3 -10 "QUEUE" "COMPLETED" false]
               ["expired admission" 3 -10 "ADMISSION" "EXPIRED" false]]]
        (testing description
          (base/execute! (str "UPDATE exam_date SET exam_date = current_date + " exam-days
                              ", registration_start_date = current_date + " (- end-days 2)
                              ", registration_end_date = current_date + " end-days " WHERE id = 1"))
          (base/execute! (str "UPDATE registration SET kind = '" kind "', state = '" state "' WHERE id = 2"))
          (let [response (peridot/request session routing/person-api-root :request-method :get)]
            (is (= 200 (get-in response [:response :status])))
            (is (= [{:id 2 :queue_ended expected}]
                   (mapv #(select-keys % [:id :queue_ended])
                         (:registrations (read-response-json response)))))))))))
