(ns yki.handler.code-test
  (:require [clojure.test :refer [deftest testing is]]
            [compojure.api.sweet :refer [api]]
            [stub-http.core :refer [with-routes!]]
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
    {"/koodisto-service/rest/json/maatjavaltiot2/koodi"
     {:status       200
      :content-type "application/json"
      :body         (slurp "test/resources/maatjavaltiot2_246.json")}}
    (let [url-helper (base/create-url-helper (str "localhost:" port))
          req-codes (mock/request :get (str routing/code-api-root "/maatjavaltiot2"))
          res-codes (send-request req-codes url-helper)
          req-code (mock/request :get (str routing/code-api-root "/maatjavaltiot2/FIN"))
          res-code (send-request req-code url-helper)
          codes (base/body-as-json res-codes)
          code (base/body-as-json res-code)]
      (testing "get codes endpoint should return 200 with codes"
        (is (= (:status res-codes) 200))
        (is (= (count codes) 3)))
      (testing "get code endpoint should return 200 with code"
        (is (= (:status res-code) 200))
        (is (= (code "koodiArvo") "FIN"))))))

