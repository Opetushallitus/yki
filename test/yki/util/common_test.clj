(ns yki.util.common-test
  (:require [clojure.test :refer :all]
            [yki.util.common :as common]))

(deftest sanitized-string-test
  (testing "sanitized-string replaces"
           (is
            (= "abc_de'``*+@&.,-_ __" (common/sanitized-string "_" "abc;de'``*+@&.,-_ ?#"))))
  (testing "sanitized-string handles nil"
           (is
            (= nil (common/sanitized-string "_" nil)))))
