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

;; ──────────────────────────────────────────────────────────────────────────────
;; session-type->subtest-flags
;; ──────────────────────────────────────────────────────────────────────────────

(deftest session-type->subtest-flags-test
  (testing "FULL session"
    (is (= {:speak 1 :write 1 :listen 1 :read 1} (yki-register/session-type->subtest-flags "FULL" "ALL_PARTS")))
    (is (= {:speak 1 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "FULL" "SPEAK")))
    (is (= {:speak 0 :write 1 :listen 0 :read 0} (yki-register/session-type->subtest-flags "FULL" "WRITE")))
    (is (= {:speak 0 :write 0 :listen 1 :read 0} (yki-register/session-type->subtest-flags "FULL" "LISTEN")))
    (is (= {:speak 0 :write 0 :listen 0 :read 1} (yki-register/session-type->subtest-flags "FULL" "READ"))))

  (testing "READ_SPEAK session"
    (is (= {:speak 1 :write 0 :listen 0 :read 1} (yki-register/session-type->subtest-flags "READ_SPEAK" "ALL_PARTS")))
    (is (= {:speak 1 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "READ_SPEAK" "SPEAK")))
    (is (= {:speak 0 :write 0 :listen 0 :read 1} (yki-register/session-type->subtest-flags "READ_SPEAK" "READ")))
    (testing "invalid combos yield 0 — WRITE and LISTEN are not offered in READ_SPEAK"
      (is (= 0 (:write  (yki-register/session-type->subtest-flags "READ_SPEAK" "ALL_PARTS"))))
      (is (= 0 (:listen (yki-register/session-type->subtest-flags "READ_SPEAK" "ALL_PARTS"))))
      (is (= {:speak 0 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "READ_SPEAK" "WRITE")))
      (is (= {:speak 0 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "READ_SPEAK" "LISTEN")))))

  (testing "LISTEN_WRITE session"
    (is (= {:speak 0 :write 1 :listen 1 :read 0} (yki-register/session-type->subtest-flags "LISTEN_WRITE" "ALL_PARTS")))
    (is (= {:speak 0 :write 1 :listen 0 :read 0} (yki-register/session-type->subtest-flags "LISTEN_WRITE" "WRITE")))
    (is (= {:speak 0 :write 0 :listen 1 :read 0} (yki-register/session-type->subtest-flags "LISTEN_WRITE" "LISTEN")))
    (testing "invalid combos yield 0 — SPEAK and READ are not offered in LISTEN_WRITE"
      (is (= 0 (:speak (yki-register/session-type->subtest-flags "LISTEN_WRITE" "ALL_PARTS"))))
      (is (= 0 (:read  (yki-register/session-type->subtest-flags "LISTEN_WRITE" "ALL_PARTS"))))
      (is (= {:speak 0 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "LISTEN_WRITE" "SPEAK")))
      (is (= {:speak 0 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags "LISTEN_WRITE" "READ")))))

  (testing "nil session type falls through to FULL branch"
    (is (= {:speak 1 :write 1 :listen 1 :read 1} (yki-register/session-type->subtest-flags nil "ALL_PARTS")))
    (is (= {:speak 1 :write 0 :listen 0 :read 0} (yki-register/session-type->subtest-flags nil "SPEAK")))))

;; ──────────────────────────────────────────────────────────────────────────────
;; merge-participants
;; ──────────────────────────────────────────────────────────────────────────────

(def ^:private base-participant
  {:person_oid    "1.1.1.1"
   :last_name     "Testi"
   :first_name    "Henkilö"
   :email         "t@test.fi"
   :zip           "00100"
   :post_office   "Helsinki"
   :street_address "Testikatu 1"
   :country_code  nil
   :is_transfered false
   :form          {:gender nil :nationalities nil :birthdate "2000-01-01"
                   :certificate_lang "fi" :exam_lang "fi"}})

(defn- row [person-oid session-type partial-type & [transferred?]]
  (assoc base-participant
         :person_oid person-oid
         :exam_session_type session-type
         :partial_exam_type partial-type
         :is_transfered (boolean transferred?)))

(deftest merge-participants-test
  (testing "single FULL/ALL_PARTS registration → all 1s"
    (let [result (first (yki-register/merge-participants [(row "p1" "FULL" "ALL_PARTS")]))]
      (is (= 1 (:speak result)))
      (is (= 1 (:write result)))
      (is (= 1 (:listen result)))
      (is (= 1 (:read result)))))

  (testing "READ_SPEAK/ALL_PARTS + LISTEN_WRITE/ALL_PARTS → all 1s (full exam across two sessions)"
    (let [result (first (yki-register/merge-participants [(row "p1" "READ_SPEAK" "ALL_PARTS")
                                                          (row "p1" "LISTEN_WRITE" "ALL_PARTS")]))]
      (is (= 1 (:speak result)))
      (is (= 1 (:write result)))
      (is (= 1 (:listen result)))
      (is (= 1 (:read result)))))

  (testing "READ_SPEAK/SPEAK + LISTEN_WRITE/WRITE → only speaking and writing"
    (let [result (first (yki-register/merge-participants [(row "p1" "READ_SPEAK" "SPEAK")
                                                          (row "p1" "LISTEN_WRITE" "WRITE")]))]
      (is (= 1 (:speak result)))
      (is (= 1 (:write result)))
      (is (= 0 (:listen result)))
      (is (= 0 (:read result)))))

  (testing "READ_SPEAK/ALL_PARTS only → speak and read, no write or listen"
    (let [result (first (yki-register/merge-participants [(row "p1" "READ_SPEAK" "ALL_PARTS")]))]
      (is (= 1 (:speak result)))
      (is (= 0 (:write result)))
      (is (= 0 (:listen result)))
      (is (= 1 (:read result)))))

  (testing "LISTEN_WRITE/LISTEN only → only listen"
    (let [result (first (yki-register/merge-participants [(row "p1" "LISTEN_WRITE" "LISTEN")]))]
      (is (= 0 (:speak result)))
      (is (= 0 (:write result)))
      (is (= 1 (:listen result)))
      (is (= 0 (:read result)))))

  (testing "is_transfered: true in one of two rows → merged result is true"
    (let [result (first (yki-register/merge-participants [(row "p1" "FULL" "ALL_PARTS" true)
                                                          (row "p1" "FULL" "ALL_PARTS" false)]))]
      (is (true? (:is_transfered result)))))

  (testing "is_transfered: false in all rows → merged result is false"
    (let [result (first (yki-register/merge-participants [(row "p1" "FULL" "ALL_PARTS" false)
                                                          (row "p1" "FULL" "ALL_PARTS" false)]))]
      (is (false? (:is_transfered result)))))

  (testing "multiple persons are kept separate"
    (let [results (yki-register/merge-participants [(row "p1" "FULL" "ALL_PARTS")
                                                    (row "p2" "READ_SPEAK" "ALL_PARTS")])]
      (is (= 2 (count results)))))

  (testing "personal info is taken from first row"
    (let [result (first (yki-register/merge-participants [(row "p1" "READ_SPEAK" "ALL_PARTS")
                                                          (row "p1" "LISTEN_WRITE" "ALL_PARTS")]))]
      (is (= "p1" (:person_oid result)))
      (is (= "Testi" (:last_name result))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; participant->csv-record — existing tests updated + new flag-position test
;; ──────────────────────────────────────────────────────────────────────────────

(deftest create-participant-csv-line-test
  (with-routes!
    {"/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_246.json")}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_180" {:status 200 :content-type "application/json"
                                                                             :body   (slurp "test/resources/maatjavaltiot2_180.json")}}
      (let [person-fields [:first_name :last_name :email :zip :post_office :street_address :country_code]]
        (testing "should create valid csv line with birth date"
          (let [participant (merge {:form          (apply dissoc base/registration-form person-fields)
                                    :person_oid    "5.4.3.2.1"
                                    :is_transfered false
                                    :speak 1 :write 1 :listen 1 :read 1}
                                   (select-keys base/registration-form person-fields))
                result      (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port)) {"5.4.3.2.1" "010199-9012"} participant)
                csv-record  ["5.4.3.2.1" "010199-9012" "Ankka" "Aku" "M" "xxx" "Katu 3" "12345" "Ankkalinna" "FIN" "aa@al.fi" "fi" "fi" 0 1 1 1 1]]
            (is (= result csv-record))))

        (testing "should create valid csv line with ssn"
          (let [registration-form-with-ssn (dissoc (assoc base/registration-form :ssn "010199-9034" :nationalities ["246"] :country_code "246") :gender)
                participant                (merge {:form          (apply dissoc registration-form-with-ssn person-fields)
                                                   :person_oid    "5.4.3.2.1"
                                                   :is_transfered true
                                                   :speak 1 :write 1 :listen 1 :read 1}
                                                  (select-keys registration-form-with-ssn person-fields))
                result                     (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port)) {"5.4.3.2.1" "010199-9034"} participant)
                csv-record                 ["5.4.3.2.1" "010199-9034" "Ankka" "Aku" "M" "FIN" "Katu 3" "12345" "Ankkalinna" "FIN" "aa@al.fi" "fi" "fi" 1 1 1 1 1]]
            (is (= result csv-record)))))))

(deftest create-participant-csv-line-missing-country-test
  (with-routes!
    {}
    (let [person-fields [:first_name :last_name :email :zip :post_office :street_address :country_code]]
      (testing "missing country codes should not call koodisto (and should become xxx)"
        (let [registration-form (-> base/registration-form
                                    (assoc :nationalities nil :country_code nil)
                                    (dissoc :gender))
              participant       (merge {:form          (apply dissoc registration-form person-fields)
                                        :person_oid    "5.4.3.2.1"
                                        :is_transfered false
                                        :speak 1 :write 1 :listen 1 :read 1}
                                       (select-keys registration-form person-fields))
              result            (yki-register/participant->csv-record (base/create-url-helper (str "localhost:" port))
                                                                      {"5.4.3.2.1" "010199-9012"}
                                                                      participant)
              csv-record        ["5.4.3.2.1" "010199-9012" "Ankka" "Aku" "M" "xxx" "Katu 3" "12345" "Ankkalinna" "xxx" "aa@al.fi" "fi" "fi" 0 1 1 1 1]]
          (is (= result csv-record)))))))

(deftest subtest-flags-in-csv-record-test
  (with-routes! {}
    (let [base-map {:form          {:gender "1" :nationalities nil :country_code nil
                                    :birthdate "1990-06-15" :certificate_lang "fi" :exam_lang "fi"}
                    :person_oid    "1.2.3.4.5"
                    :is_transfered false
                    :last_name "Mäkinen" :first_name "Matti" :email "m@m.fi"
                    :zip "33100" :post_office "Tampere" :street_address "Linja 1" :country_code nil}
          url-helper (base/create-url-helper (str "localhost:" port))
          oid->ssn   {"1.2.3.4.5" "150690-900T"}]
      (testing "READ_SPEAK/ALL_PARTS flags: speak=1 write=0 listen=0 read=1"
        (let [result (yki-register/participant->csv-record url-helper oid->ssn
                                                           (assoc base-map :speak 1 :write 0 :listen 0 :read 1))]
          (is (= 1 (nth result 14)))
          (is (= 0 (nth result 15)))
          (is (= 0 (nth result 16)))
          (is (= 1 (nth result 17)))))
      (testing "LISTEN_WRITE/ALL_PARTS flags: speak=0 write=1 listen=1 read=0"
        (let [result (yki-register/participant->csv-record url-helper oid->ssn
                                                           (assoc base-map :speak 0 :write 1 :listen 1 :read 0))]
          (is (= 0 (nth result 14)))
          (is (= 1 (nth result 15)))
          (is (= 1 (nth result 16)))
          (is (= 0 (nth result 17)))))
      (testing "single subtest SPEAK only: speak=1 rest=0"
        (let [result (yki-register/participant->csv-record url-helper oid->ssn
                                                           (assoc base-map :speak 1 :write 0 :listen 0 :read 0))]
          (is (= 1 (nth result 14)))
          (is (= 0 (nth result 15)))
          (is (= 0 (nth result 16)))
          (is (= 0 (nth result 17))))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; create-participants-csv — end-to-end with mixed session types
;; ──────────────────────────────────────────────────────────────────────────────

(deftest create-participants-csv-mixed-session-types-test
  (with-routes! {}
    (let [url-helper (base/create-url-helper (str "localhost:" port))
          oid->ssn   {"p1" "" "p2" "" "p3" "" "p4" "" "p5" ""}
          make-row   (fn [oid session-type partial-type]
                       {:person_oid     oid
                        :exam_session_type session-type
                        :partial_exam_type partial-type
                        :is_transfered  false
                        :last_name      "Testi" :first_name "Henkilö"
                        :email          "t@test.fi" :zip "00100"
                        :post_office    "Helsinki" :street_address "Tie 1" :country_code nil
                        :form           {:gender nil :nationalities nil
                                         :birthdate "2000-01-01"
                                         :certificate_lang "fi" :exam_lang "fi"}})
          participants [(make-row "p1" "FULL"         "ALL_PARTS")   ; → 1 1 1 1
                        (make-row "p2" "READ_SPEAK"   "ALL_PARTS")   ; → 1 0 0 1
                        (make-row "p3" "LISTEN_WRITE" "ALL_PARTS")   ; → 0 1 1 0
                        (make-row "p4" "READ_SPEAK"   "ALL_PARTS")   ; \  merged
                        (make-row "p4" "LISTEN_WRITE" "ALL_PARTS")   ; /  → 1 1 1 1
                        (make-row "p5" "READ_SPEAK"   "SPEAK")       ; \  merged
                        (make-row "p5" "LISTEN_WRITE" "LISTEN")]     ; /  → 1 0 1 0
          csv-str (yki-register/create-participants-csv url-helper participants oid->ssn)
          rows    (str/split csv-str #"\n")
          fields  (fn [row-str] (str/split row-str #";"))]
      (testing "five distinct persons in output (p4 and p5 are merged)"
        (is (= 5 (count rows))))
      (testing "p1 FULL/ALL_PARTS → speak=1 write=1 listen=1 read=1"
        (let [f (fields (first (filter #(str/starts-with? % "p1") rows)))]
          (is (= ["1" "1" "1" "1"] (subvec (vec f) 14 18)))))
      (testing "p2 READ_SPEAK/ALL_PARTS → speak=1 write=0 listen=0 read=1"
        (let [f (fields (first (filter #(str/starts-with? % "p2") rows)))]
          (is (= ["1" "0" "0" "1"] (subvec (vec f) 14 18)))))
      (testing "p3 LISTEN_WRITE/ALL_PARTS → speak=0 write=1 listen=1 read=0"
        (let [f (fields (first (filter #(str/starts-with? % "p3") rows)))]
          (is (= ["0" "1" "1" "0"] (subvec (vec f) 14 18)))))
      (testing "p4 READ_SPEAK+LISTEN_WRITE both ALL_PARTS → merged speak=1 write=1 listen=1 read=1"
        (let [f (fields (first (filter #(str/starts-with? % "p4") rows)))]
          (is (= ["1" "1" "1" "1"] (subvec (vec f) 14 18)))))
      (testing "p5 READ_SPEAK/SPEAK + LISTEN_WRITE/LISTEN → merged speak=1 write=0 listen=1 read=0"
        (let [f (fields (first (filter #(str/starts-with? % "p5") rows)))]
          (is (= ["1" "0" "1" "0"] (subvec (vec f) 14 18))))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; sync-exam-session-participants — full integration against embedded DB
;; ──────────────────────────────────────────────────────────────────────────────

(def csv (str/join (System/lineSeparator) ["5.4.3.2.2;301079-900U;Ankka;Iines;N;FIN;Katu 4;12346;Ankkalinna;FIN;aa@al.fi;fi;fi;0;1;1;1;1" "5.4.3.2.1;010199-9012;Ankka;Aku;M;xxx;Katu 3;12345;Ankkalinna;FIN;aa@al.fi;fi;fi;0;1;1;1;1" "5.4.3.2.4;301079-083N;Ankka;Roope;M;FIN;Katu 5;12346;Ankkalinna;FIN;roope@al.fi;fi;fi;0;1;1;1;1"]))

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
