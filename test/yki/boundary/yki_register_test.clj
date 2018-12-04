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
                   :language_code "fi"
                   :level_code "PERUS"
                   :session_date "2039-05-02"
                   :max_participants 50
                   :published_at "2018-01-01T00:00:00.000Z"
                   :organizer_oid "1.2.3.4"
                   :office_oid "1.2.3.5"})

(def assert-exam-session-req {:tutkintokieli "fi"
                              :taso "PT"
                              :pvm "2039-05-02"
                              :jarjestaja "1.2.3.5"})

(deftest sync-exam-session-requests-test
  (let [organizer (j/read-value (slurp "test/resources/organizer.json"))
        organization (j/read-value (slurp "test/resources/organization.json"))
        assert-organizer-req (j/read-value (slurp "test/resources/organizer_sync_req.json") (j/object-mapper {:decode-key-fn true}))
        organizer-req (yki-register/create-sync-organizer-req organizer organization)
        exam-session-req (yki-register/create-sync-exam-session-req exam-session)]
    (testing "organizer sync request is valid"
      (is (= organizer-req assert-organizer-req)))
    (testing "exam session sync request is valid"
      (is (= exam-session-req assert-exam-session-req)))))

(deftest delete-exam-session-and-organizer-test
  (base/insert-login-link-prereqs)
  (testing "should send delete requests"
    (with-routes!
      {{:path "/tutkintotilaisuus" :query-params {:tutkintokieli "fi" :taso "PT" :pvm "2018-01-27" :jarjestaja "1.2.3.4.5"}} {:status 202}
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
