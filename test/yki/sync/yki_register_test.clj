(ns yki.sync.yki-register-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [jsonista.core :as j]
            [yki.sync.yki-register :as yki-register]))

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
