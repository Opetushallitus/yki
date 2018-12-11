(ns yki.util.template-util-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [integrant.core :as ig]
            [stub-http.core :refer :all]
            [yki.handler.base-test :as base]
            [yki.util.template-util :as template-util]))

(deftest render-login-link-email-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [url-helper (base/create-url-helper (str "localhost:" port))
          rendered (template-util/render url-helper "LOGIN" "fi" {:login-url "http://localhost:8080/login"})]
      (testing "result contains login link"
        (is (s/includes? rendered "Kirjaudu"))
        (is (s/includes? rendered "http://localhost:8080/login"))))))

(deftest render-payment-link-email-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [url-helper (base/create-url-helper (str "localhost:" port))
          rendered (template-util/render url-helper "PAYMENT" "fi" {:login-url "http://localhost:8080/payment"
                                                                    :language_code "fi"
                                                                    :amount "100.00"
                                                                    :level_code "PERUS"
                                                                    :exam_date "2018-01-07"
                                                                    :street_address "Upseerinkatu 11"
                                                                    :city "Espoo"})]
      (testing "exam date is formatted correctly"
        (is (s/includes? rendered "7.1.2018")))
      (testing "amount is formatted correctly"
        (is (s/includes? rendered "100,00 €")))
      (testing "result contains payment link"
        (is (s/includes? rendered "http://localhost:8080/payment"))))))
