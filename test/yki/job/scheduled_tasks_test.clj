(ns yki.job.scheduled-tasks-test
  (:require [clojure.test :refer :all]
            [stub-http.core :refer :all]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [clojure.java.jdbc :as jdbc]
            [yki.util.common :as c]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [yki.handler.base-test :as base]
            [yki.boundary.exam-session-db :as exam-session-db]
            [yki.embedded-db :as embedded-db]
            [yki.job.scheduled-tasks :as st]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(def email-req
  {:recipients ["test@test.com"]
   :created (System/currentTimeMillis)
   :subject "subject"
   :body "body"})

(defn create-email-q-reader
  [port retry-duration-in-days]
  (ig/init-key :yki.job.scheduled-tasks/email-queue-reader {:url-helper (base/create-url-helper (str "localhost:" port))
                                                            :basic-auth {:user "user" :password "pass"}
                                                            :retry-duration-in-days retry-duration-in-days
                                                            :email-q (base/email-q)}))

(def date-formatter (f/formatter c/date-format))

(defn yesterday []
  (f/unparse (f/formatter c/date-format) (t/minus (t/now) (t/days 1))))

(defn two-weeks-ago []
  (f/unparse (f/formatter c/date-format) (t/minus (t/now) (t/days 14))))

(deftest handle-exam-session-participants-sync-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set registration_end_date = '" (yesterday) "'"))
  (with-routes!
    {"/osallistujat" {:status 200
                      :body "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body (slurp "test/resources/maatjavaltiot2_246.json")}}
    (let [handler (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db (base/db)
                                                                                   :disabled false
                                                                                   :basic-auth {:user "user" :password "pass"}
                                                                                   :retry-duration-in-days 14
                                                                                   :url-helper (base/create-url-helper (str "localhost:" port))})
          _ (handler)
          sync_status (base/select-one "SELECT * FROM participant_sync_status")]
      (testing "should send participants to yki register and set sync status to success"
        (is (= (count (:recordings (first @(:routes server)))) 1))
        (is (some? (:success_at sync_status))))
      (testing "should send participants only once"
        (handler)
        (is (= (count (:recordings (first @(:routes server)))) 1))))))

(deftest handle-exam-session-participants-failure-test
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")

  (jdbc/execute! @embedded-db/conn (str "UPDATE exam_date set registration_end_date = '" (two-weeks-ago) "'"))
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO participant_sync_status (exam_session_id, failed_at) VALUES (1, '" (yesterday) "')"))

  (with-routes!
    {"/osallistujat" {:status 500
                      :body "{}"}
     "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot2_246" {:status 200 :content-type "application/json"
                                                                             :body (slurp "test/resources/maatjavaltiot2_246.json")}}
    (let [handler (ig/init-key :yki.job.scheduled-tasks/participants-sync-handler {:db (base/db)
                                                                                   :disabled false
                                                                                   :basic-auth {:user "user" :password "pass"}
                                                                                   :retry-duration-in-days 14
                                                                                   :url-helper (base/create-url-helper (str "localhost:" port))})
          failed_at_before (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))
          _ (handler)
          failed_at_after (:failed_at (base/select-one "SELECT failed_at FROM participant_sync_status"))]
      (testing "should update failed at timestamp"
        (is (t/after? failed_at_after failed_at_before))))))
