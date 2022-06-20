(ns yki.handler.exam-date-public-test
  (:require [clojure.test :refer [deftest use-fixtures join-fixtures testing is]]
            [compojure.api.sweet :refer [api]]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.exam-date-public]
            [yki.handler.routing :as routing]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction]))

(defn- send-request [request]
  (let [handler (api (ig/init-key :yki.handler/exam-date-public {:db (base/db)}))]
    (handler request)))

(deftest get-exam-dates-test
  (let [request (mock/request :get routing/exam-date-api-root)
        response (send-request request)]
    (testing "should return 200"
      (is (= (:status response) 200)))))

