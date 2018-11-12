(ns yki.job.scheduled-tasks-test
  (:require [clojure.test :refer :all]
            [stub-http.core :refer :all]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.job.scheduled-tasks :as st]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(def email-req
  {:recipients ["test@test.com"]
   :subject "subject"
   :body "body"})

(deftest handle-email-request-test
  (with-routes!
    {"/ryhmasahkoposti-service/email/firewall" {:status 200 :content-type "application/json"
                                                :body   (j/write-value-as-string {:id 1})}}
    (let [email-q (ig/init-key :yki.job.job-queue/email-q {:db-config {:db embedded-db/db-spec}})
          _ (pgq/put email-q email-req)
          reader (ig/init-key :yki.job.scheduled-tasks/email-queue-reader {:url-helper (base/url-helper (str "localhost:" port)) :email-q email-q})]
      (testing "should read email request from queue and send email"
        (is (= (pgq/count email-q) 1))
        (reader)
        (is (= (count (:recordings (first @(:routes server)))) 1))
        (is (= (pgq/count email-q) 0))))))
