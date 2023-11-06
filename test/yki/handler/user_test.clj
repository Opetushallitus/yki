(ns yki.handler.user-test
  (:require [clj-time.local :as l]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest use-fixtures testing is]]
            [jsonista.core :as j]
            [peridot.core :as peridot]
            [stub-http.core :refer [with-routes!]]
            [yki.boundary.onr]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.registration-commons :refer [common-bindings
                                                      common-route-specs
                                                      fill-exam-session
                                                      insert-common-base-data
                                                      registration-form-data
                                                      registration-success-redirect]]
            [yki.handler.routing :as routing]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- insert-initial-data! []
  (let [organizer-oid "1.2.3.5"]
    (insert-common-base-data organizer-oid)
    (base/insert-exam-session 1 organizer-oid 50)
    (base/insert-exam-session-location organizer-oid "fi")
    (base/insert-exam-session-location organizer-oid "sv")
    (base/insert-exam-session-location organizer-oid "en")
    (base/insert-login-link base/code-ok "2038-01-01")
    (jdbc/execute! @embedded-db/conn "INSERT INTO exam_session_queue (email, lang, exam_session_id) VALUES ('test@test.com', 'sv', 1)")))

(deftest user-handler-test
  (insert-initial-data!)
  (with-routes!
    common-route-specs
    (let [{session               :session} (common-bindings server)]
      (testing "get user identity should return external-user-id"
        (let [identity (base/body-as-json (:response (-> session
                                                         (peridot/request
                                                          (str routing/user-api-root "/identity")
                                                          :content-type "application/json"))))]
          (is (some? (get-in identity ["identity" "external-user-id"])))))

      (testing "get user open registrations endpoint should return session id"
        (let [user-open-registrations-response (base/body-as-json (:response (-> session
                                                                                 (peridot/request (str routing/user-api-root "/open-registrations")
                                                                                                  :content-type "application/json"))))]
          (is (= (get-in user-open-registrations-response ["open_registrations" 0 "exam_session_id"]) 1))
          (is (some? (get-in user-open-registrations-response ["open_registrations" 0 "expires_at"]))))))))
