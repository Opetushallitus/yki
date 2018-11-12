(ns yki.util.template-util-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [yki.util.template-util :as template-util]))

(deftest render-login-link-email-test
  (let [rendered (yki.util.template-util/render "login_link" "fi" {:login-url "http://localhost:8080/login"})]
    (testing "result contains login link"
      (is (s/includes? rendered "http://localhost:8080/login")))))
