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

(deftest render-payment-success-email-test
  (let [base-data {:language       "Finnish"
                   :level          "Basic level"
                   :exam_date      "2024-05-16"
                   :street_address "Katutie 13"
                   :zip            "00500"
                   :post_office    "Helsinki"
                   :name           "Järjestäjä Oy"}]

    (testing "mail contains proper content for base template data"
      (let [rendered (template-util/render "PAYMENT_SUCCESS" "en" base-data)]
        (is (s/includes? rendered "Test: Finnish basic level"))
        (is (s/includes? rendered "Test day: 16.5.2024"))
        (is (s/includes? rendered "Test centre: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
        (is (not (s/includes? rendered "Additional information from the organizer")))
        (is (not (s/includes? rendered "Test centre's contact information")))))

    (testing "mail contains proper content with extra information and organizer's contact info"
      (let [template-data (assoc base-data :extra_information "Be on time"
                                           :contact_info {:name         "Foo Bar"
                                                          :email        "foo@bar"
                                                          :phone_number "+358123"})
            rendered      (template-util/render "PAYMENT_SUCCESS" "en" template-data)]
        (is (s/includes? rendered "Test: Finnish basic level"))
        (is (s/includes? rendered "Test day: 16.5.2024"))
        (is (s/includes? rendered "Test centre: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
        (is (s/includes? rendered "Additional information from the organizer"))
        (is (s/includes? rendered "Be on time"))
        (is (s/includes? rendered "Test centre's contact information"))
        (is (s/includes? rendered "Name: Foo Bar"))
        (is (s/includes? rendered "Email address: foo@bar"))
        (is (s/includes? rendered "Phone: +358123"))))))

(deftest render-queue-email-test
  (let [rendered (template-util/render "QUEUE" "fi" {:language         "Suomi"
                                                     :level            "Ylin taso"
                                                     :exam_date        "2024-05-16"
                                                     :street_address   "Katutie 13"
                                                     :zip              "00500"
                                                     :post_office      "Helsinki"
                                                     :name             "Järjestäjä Oy"
                                                     :exam_session_url "http://localhost:8080/exam-session"})]
    (testing "result contains proper content"
      (is (s/includes? rendered "Tutkinto: Suomi ylin taso"))
      (is (s/includes? rendered "Testipäivä: 16.5.2024"))
      (is (s/includes? rendered "Testipaikka: Järjestäjä Oy, Katutie 13, 00500 HELSINKI"))
      (is (s/includes? rendered "YKI-testissä on vapaita paikkoja"))
      (is (s/includes? rendered "http://localhost:8080/exam-session")))))
