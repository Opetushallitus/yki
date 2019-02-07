(ns yki.handler.code-test
  (:require [clojure.test :refer :all]
            [compojure.api.sweet :refer :all]
            [stub-http.core :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.handler.code]))

(defn- send-request [request url-helper]
  (let [handler (api (ig/init-key :yki.handler/code {:url-helper url-helper}))]
    (handler request)))

(deftest get-codes-test
  (with-routes!
    {"/koodisto-service/rest/json/testcode/koodi?onlyValidKoodis=true"
     {:status       200
      :content-type "application/json"
      :body         (slurp "test/resources/localisation.json")}}
    (let [url-helper (base/create-url-helper (str "localhost:" port))
          request (mock/request :get (str routing/code-api-root "/testcode"))
          response (send-request request url-helper)]
      (testing "get codes endpoint should return 200"
        (is (= (:status response) 200))))))

