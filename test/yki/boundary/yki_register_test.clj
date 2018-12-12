(ns yki.boundary.yki-register-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [yki.handler.base-test :as base]
            [stub-http.core :refer :all]
            [jsonista.core :as j]
            [yki.embedded-db :as embedded-db]
            [yki.boundary.yki-register :as yki-register]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(def exam-session {:id 1
                   :language_code "fin"
                   :level_code "PERUS"
                   :session_date "2039-05-02"
                   :max_participants 50
                   :published_at "2018-01-01T00:00:00.000Z"
                   :organizer_oid "1.2.3.4"
                   :office_oid "1.2.3.5"})

(def assert-exam-session-req {:tutkintokieli "fin"
                              :taso "PT"
                              :pvm "2039-05-02"
                              :jarjestaja "1.2.3.5"})

(deftest sync-exam-session-requests-test
  (let [organizer (j/read-value (slurp "test/resources/organizer.json") (j/object-mapper {:decode-key-fn true}))
        organization (j/read-value (slurp "test/resources/organization.json"))
        assert-organizer-req (j/read-value (slurp "test/resources/organizer_sync_req.json") (j/object-mapper {:decode-key-fn true}))
        organizer-req (yki-register/create-sync-organizer-req organizer organization)
        exam-session-req (yki-register/create-sync-exam-session-req exam-session)]
    (testing "organizer sync request is valid"
      (is (= organizer-req assert-organizer-req)))
    (testing "exam session sync request is valid"
      (is (= exam-session-req assert-exam-session-req)))))

(deftest create-participant-csv-line-test
  (testing "should create valid csv line with birth date"
    (let [result (yki-register/create-partipant-csv base/registration-form "5.4.3.2.1")
          csv-line "5.4.3.2.1;01011999-;Aku;Ankka;M;FIN;Katu 3;12345;Ankkalinna;aa@al.fi;fi;fi"]
      (is (= result csv-line))))

  (testing "should create valid csv line with ssn"
    (let [registration-form-with-ssn (assoc base/registration-form :ssn "01011999-123A")
          result (yki-register/create-partipant-csv registration-form-with-ssn  "5.4.3.2.1")
          csv-line "5.4.3.2.1;01011999-123A;Aku;Ankka;M;FIN;Katu 3;12345;Ankkalinna;aa@al.fi;fi;fi"]
      (is (= result csv-line)))))

(deftest delete-exam-session-and-organizer-test
  (base/insert-login-link-prereqs)
  (testing "should send delete requests"
    (with-routes!
      {{:path "/tutkintotilaisuus" :query-params {:tutkintokieli "fin" :taso "PT" :pvm "2018-01-27" :jarjestaja "1.2.3.4.5"}} {:status 202}
       {:path "/jarjestaja" :query-params {:oid "1.2.3.4"}} {:status 202}}
      (let [exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
            db (base/db)
            url-helper (base/create-url-helper (str "localhost:" port))
            delete-organizer-req  {:organizer-oid "1.2.3.4"
                                   :type "DELETE"
                                   :created (System/currentTimeMillis)}
            delete-exam-session-req  {:exam-session-id exam-session-id
                                      :type "DELETE"
                                      :created (System/currentTimeMillis)}
            delete-organizer-res (yki-register/sync-exam-session-and-organizer db url-helper false delete-organizer-req)
            delete-exam-session-res (yki-register/sync-exam-session-and-organizer db url-helper false delete-exam-session-req)]))))

(def csv (s/join (System/lineSeparator) ["5.4.3.2.2;301079-122F;Aku2;Ankka2;M;FIN;Katu 4;12346;Ankkalinna;aa@al.fi;fi;fi" "5.4.3.2.1;01011999-;Aku;Ankka;M;FIN;Katu 3;12345;Ankkalinna;aa@al.fi;fi;fi"]))

(deftest sync-exam-session-participants-test
  (base/insert-login-link-prereqs)
  (base/insert-registrations)
  (testing "should send participants as csv"
    (with-routes!
      {{:path "/osallistujat" :query-params {:tutkintokieli "fin" :taso "PT" :pvm "2018-01-27" :jarjestaja "1.2.3.4.5"}} {:status 200}}
      (let [exam-session-id (:id (base/select-one "SELECT id FROM exam_session"))
            db (base/db)
            url-helper (base/create-url-helper (str "localhost:" port))
            _ (yki-register/sync-exam-session-participants db url-helper false exam-session-id)
            request (first (:recordings (first @(:routes server))))
            req-body (get-in request [:request :body "postData"])]
        (is (= req-body csv))))))
