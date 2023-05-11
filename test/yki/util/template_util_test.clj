(ns yki.util.template-util-test
  (:require
    [clojure.string :as s]
    [clojure.test :refer [deftest is testing]]
    [yki.util.template-util :as template-util]))

(deftest render-login-email-test
  (let [rendered (template-util/render "LOGIN" "fi" {:language       "Suomi"
                                                     :level          "Ylin taso"
                                                     :exam_date      "2024-05-16"
                                                     :street_address "Katutie 13"
                                                     :zip            "00500"
                                                     :post_office    "Helsinki"
                                                     :name           "Järjestäjä Oy"
                                                     :login_url      "http://localhost:8080/login"})]
    (testing "result contains proper content"
      (is (s/includes? rendered "Tutkinto: Suomi ylin taso"))
      (is (s/includes? rendered "Testipäivä: 16.5.2024"))
      (is (s/includes? rendered "Testipaikka: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
      (is (s/includes? rendered "Ilmoittaudu testiin tämän linkin kautta"))
      (is (s/includes? rendered "http://localhost:8080/login")))))

(deftest render-payment-email-test
  (let [rendered (template-util/render "PAYMENT" "sv" {:amount          "100.00"
                                                       :expiration_date "24.4.2024"
                                                       :language        "Svenska"
                                                       :level           "Mellannivå"
                                                       :exam_date       "2024-05-16"
                                                       :street_address  "Katutie 13"
                                                       :zip             "00500"
                                                       :post_office     "Helsinki"
                                                       :name            "Järjestäjä Oy"
                                                       :login_url       "http://localhost:8080/payment"})]
    (testing "result contains proper content"
      (is (s/includes? rendered "Test: Svenska mellannivå"))
      (is (s/includes? rendered "Testdatum: 16.5.2024"))
      (is (s/includes? rendered "Testställe: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
      (is (s/includes? rendered "Examensavgift: 100,00 €"))
      (is (s/includes? rendered "Betala avgiften senast 24.4.2024, annars återkallas din anmälan."))
      (is (s/includes? rendered "http://localhost:8080/payment")))))
