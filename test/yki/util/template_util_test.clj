(ns yki.util.template-util-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.string :as s]
    [yki.util.template-util :as template-util]))

(deftest render-login-link-email-test
  (let [rendered (template-util/render "LOGIN" "fi" {:language "Suomi"
                                                     :level "Ylin taso"
                                                     :exam_date "2024-05-16"
                                                     :street_address "Katutie 13"
                                                     :zip "00500"
                                                     :post_office "Helsinki"
                                                     :name "Järjestäjä Oy"
                                                     :login_url "http://localhost:8080/login"})]
    (testing "result contains proper content"
      (is (s/includes? rendered "Tutkinto: Suomi ylin taso"))
      (is (s/includes? rendered "Testipäivä: 16.5.2024"))
      (is (s/includes? rendered "Testipaikka: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
      (is (s/includes? rendered "http://localhost:8080/login")))))

(deftest render-payment-link-email-test
  (let [rendered   (template-util/render "PAYMENT" "fi" {:login-url     "http://localhost:8080/payment"
                                                         :language_code "fi"
                                                         :amount        "100.00"
                                                         :level_code    "PERUS"
                                                         :exam_date     "2018-01-07"
                                                         :address       "Upseerinkatu 11, Espoo"})]
    (testing "exam date is formatted correctly"
      (is (s/includes? rendered "7.1.2018")))
    (testing "amount is formatted correctly"
      (is (s/includes? rendered "100,00 €")))
    (testing "result contains payment link"
      (is (s/includes? rendered "http://localhost:8080/payment")))))
