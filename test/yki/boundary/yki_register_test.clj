(ns yki.boundary.yki-register-test
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [yki.handler.base-test :as base]
    [yki.handler.registration-commons :refer [common-route-specs]]
    [stub-http.core :refer [with-routes!]]
    [jsonista.core :as j]
    [yki.embedded-db :as embedded-db]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.boundary.yki-register :as yki-register]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def exam-session {:id               1
                   :language_code    "fin"
                   :level_code       "PERUS"
                   :session_date     "2039-05-02"
                   :max_participants 50
                   :published_at     "2018-01-01T00:00:00.000Z"
                   :organizer_oid    "1.2.3.4"
                   :office_oid       "1.2.3.5"})

(def assert-exam-session-req {:kieli      "fin"
                              :taso       "PT"
                              :pvm        "2039-05-02"
                              :jarjestaja "1.2.3.5"})

(deftest sync-exam-session-requests-test
  (let [organizer            (j/read-value (slurp "test/resources/organizer.json") (j/object-mapper {:decode-key-fn true}))
        organization         (j/read-value (slurp "test/resources/organization.json"))
        assert-organizer-req (j/read-value (slurp "test/resources/organizer_sync_req.json") (j/object-mapper {:decode-key-fn true}))
        organizer-req        (yki-register/create-sync-organizer-req organizer organization)
        exam-session-req     (yki-register/create-sync-exam-session-req exam-session)]
    (testing "organizer sync request is valid"
      (is (= organizer-req assert-organizer-req)))
    (testing "exam session sync request is valid"
      (is (= exam-session-req assert-exam-session-req)))))

(deftest create-participant-csv-line-test
  (with-routes!
    {"/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
      (let [person-fields [:first_name :last_name :email :zip :post_office :street_address]]
        (testing "should create valid csv line with birth date"
          (let [participant (merge {:form          (apply dissoc base/registration-form person-fields)
                                    :person_oid    "5.4.3.2.1"
                                    :is_transfered false}
                                   (select-keys base/registration-form person-fields))
                result      (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port)) {"5.4.3.2.1" "010199-9012"} participant)
                csv-record  ["5.4.3.2.1" "010199-9012" "Ankka" "Aku" "M" "xxx" "Katu 3" "12345" "Ankkalinna" "FIN" "aa@al.fi" "fi" "fi" 0]]
            (is (= result csv-record))))

        (testing "should create valid csv line with ssn"
          (let [registration-form-with-ssn (dissoc (assoc base/registration-form :ssn "010199-9034" :nationalities ["246"] :country_code "246") :gender)
                participant                (merge {:form          (apply dissoc registration-form-with-ssn person-fields)
                                                   :person_oid    "5.4.3.2.1"
                                                   :is_transfered true}
                                                  (select-keys registration-form-with-ssn person-fields))
                result                     (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port)) {"5.4.3.2.1" "010199-9034"} participant)
                csv-record                 ["5.4.3.2.1" "010199-9034" "Ankka" "Aku" "M" "FIN" "Katu 3" "12345" "Ankkalinna" "FIN" "aa@al.fi" "fi" "fi" 1]]
            (is (= result csv-record)))))))

(deftest create-participant-csv-line-missing-country-test
  (with-routes!
    {}
    (let [person-fields [:first_name :last_name :email :zip :post_office :street_address]]
      (testing "missing country codes should not call koodisto (and should become xxx)"
        (let [registration-form (-> base/registration-form
                                    (assoc :nationalities nil :country_code nil)
                                    (dissoc :gender))
              participant       (merge {:form          (apply dissoc registration-form person-fields)
                                        :person_oid    "5.4.3.2.1"
                                        :is_transfered false}
                                       (select-keys registration-form person-fields))
              result            (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port))
                                                                      {"5.4.3.2.1" "010199-9012"}
                                                                      participant)
              csv-record        ["5.4.3.2.1" "010199-9012" "Ankka" "Aku" "M" "xxx" "Katu 3" "12345" "Ankkalinna" "xxx" "aa@al.fi" "fi" "fi" 0]]
          (is (= result csv-record)))))))

(deftest delete-exam-session-and-organizer-test
  (base/insert-base-data)
  (testing "should send delete requests"
    (with-routes!
      {{:path "/oph/tutkintotilaisuus" :query-params {:kieli "fin" :taso "PT" :pvm "2018-01-27" :jarjestaja "1.2.3.4.5"}} {:status 202}
       {:path "/oph/jarjestaja" :query-params {:oid "1.2.3.4"}}                                                           {:status 202}}
      (let [exam-session-id          (:id (base/select-one "SELECT id FROM exam_session"))
            db                       (base/db)
            es                       (exam-session-db/get-exam-session-by-id db exam-session-id)
            url-helper               (base/create-url-helper (str "localhost:" port))
            delete-organizer-req     {:organizer-oid "1.2.3.4"
                                      :type          "DELETE"
                                      :created       (System/currentTimeMillis)}
            delete-exam-session-req  {:exam-session es
                                      :type         "DELETE"
                                      :created      (System/currentTimeMillis)}
            _delete-organizer-res    (yki-register/sync-exam-session-and-organizer db url-helper {:user "user" :password "pass"} false delete-organizer-req)
            _delete-exam-session-res (yki-register/sync-exam-session-and-organizer db url-helper {:user "user" :password "pass"} false delete-exam-session-req)]
        "tests that exception is not thrown"))))

(def csv (str/join (System/lineSeparator) ["5.4.3.2.2;301079-900U;Ankka;Iines;N;FIN;Katu 4;12346;Ankkalinna;FIN;aa@al.fi;fi;fi;0" "5.4.3.2.1;010199-9012;Ankka;Aku;M;xxx;Katu 3;12345;Ankkalinna;FIN;aa@al.fi;fi;fi;0" "5.4.3.2.4;301079-083N;Ankka;Roope;M;FIN;Katu 5;12346;Ankkalinna;FIN;roope@al.fi;fi;fi;0"]))

(deftest sync-exam-session-participants-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "COMPLETED")
  (defn routes [port]
    (merge (common-route-specs port)
           {{:path "/oph/osallistujat" :query-params {:kieli "fin" :taso "PT" :pvm "2018-01-27" :jarjestaja "1.2.3.4.5"}} {:status 200}
            "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246"                                               {:status 200 :content-type "application/json"
                                                                                                                                  :body   (slurp "test/resources/maatjavaltiot2_246.json")}
            "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180"                                               {:status 200 :content-type "application/json"
                                                                                                                                  :body   (slurp "test/resources/maatjavaltiot2_180.json")}}))
  (testing "should send participants as csv and add basic auth header"
    (with-routes! routes
        (let [exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
              db              (base/db)
              url-helper      (base/create-url-helper (str "localhost:" port))
              onr-client      (base/onr-client url-helper)
              _               (yki-register/sync-exam-session-participants db url-helper onr-client {:user "user" :password "pass"} false exam-session-id)
              request         (first (:recordings (first (filter #(= "/oph/osallistujat" (get-in % [:request-spec :path])) @(:routes server)))))
              req-body        (get-in request [:request :body "postData"])]
          (is (= (get-in request [:request :headers :authorization]) "Basic dXNlcjpwYXNz"))
          (is (= req-body csv)))))
    (testing "participants csv should look up contact details for participant from the person table"
      (with-routes! routes
          (let [old-email "aa@al.fi"
                new-email "updated@test.invalid"]
            (jdbc/execute! @embedded-db/conn (str "UPDATE person SET email='" new-email "' WHERE email='" old-email "'"))
            (let [exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
                  db              (base/db)
                  url-helper      (base/create-url-helper (str "localhost:" port))
                  onr-client      (base/onr-client url-helper)
                  _               (yki-register/sync-exam-session-participants db url-helper onr-client {:user "user" :password "pass"} false exam-session-id)
                  request         (first (:recordings (first (filter #(= "/oph/osallistujat" (get-in % [:request-spec :path])) @(:routes server)))))
                  req-body        (get-in request [:request :body "postData"])]
              (is (= req-body (str/replace csv old-email new-email))))))))

(deftest sync-exam-session-participants-schedule-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "COMPLETED")
  (let [exam-session-id (:id (base/select-one base/select-exam-session))
        exam-date-id    (:exam_date_id (base/select-one (str "SELECT exam_date_id FROM exam_session WHERE id=" exam-session-id)))
        db              (base/db)
        retry-period    "1 days"]
    (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET exam_date = current_date + interval '1 month' WHERE id=" exam-session-id))
    (testing "If current date is before start of registration period, exam session participants should not be synced"
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET registration_start_date = (current_date + interval '1 days') WHERE id=" exam-date-id))
      (is (= [] (exam-session-db/get-exam-sessions-to-be-synced db retry-period))))
    (testing "If registration period is ongoing, exam session participants should be synced"
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET registration_start_date = (current_date - interval '1 week'), registration_end_date = (current_date + interval '1 week') WHERE id=" exam-date-id))
      (is (= [exam-session-id] (map :exam_session_id (exam-session-db/get-exam-sessions-to-be-synced db retry-period)))))
    (testing "If registration period is over but there is still a week until exam session, exam session participants should be synced"
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET registration_end_date = (current_date - interval '1 days'), exam_date=(current_date + interval '1 week') WHERE id=" exam-date-id))
      (is (= [exam-session-id] (map :exam_session_id (exam-session-db/get-exam-sessions-to-be-synced db retry-period))))
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET exam_date=(current_date + interval '1 week' - interval '1 day') WHERE id=" exam-date-id))
      (is (= [exam-session-id] (map :exam_session_id (exam-session-db/get-exam-sessions-to-be-synced db retry-period)))))
    (testing "To accommodate last minute payments, sync period is extended one full day; sync is thus performed up to 6 days before exam date"
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET exam_date=(current_date + interval '1 week' - interval '" retry-period "') WHERE id=" exam-date-id))
      (is (= [exam-session-id] (map :exam_session_id (exam-session-db/get-exam-sessions-to-be-synced db retry-period))))
      (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date SET exam_date=(current_date + interval '6 days' - interval '" retry-period "') WHERE id=" exam-date-id))
      (is (= [] (map :exam_session_id (exam-session-db/get-exam-sessions-to-be-synced db retry-period)))))))
