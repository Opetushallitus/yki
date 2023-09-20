(ns yki.spec-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.spec.alpha :as s]
    [yki.spec :as ys]))

(deftest email-validation-test
  (testing "email validation"
    (testing "allows normal unicode characters"
      (is (s/valid? ::ys/email "email@test.invalid"))
      (is (s/valid? ::ys/email "höpö@pöhkö.invalid"))
      (is (s/valid? ::ys/email "alaviiva_sallittu+subject@test.invalid"))
      (is (s/valid? ::ys/email "français@mærsk.no"))
      (is (s/valid? ::ys/email "user.1.example@foo.bar")))
    (testing "does not allow potentially problematic characters"
      (is (not (s/valid? ::ys/email "email@<script>alert('moi')</script>.fi")))
      (is (not (s/valid? ::ys/email "<script>i++</script>@foo.bar")))
      (is (not (s/valid? ::ys/email "testi\"@foo.bar")))
      (is (not (s/valid? ::ys/email "puolipiste;kielletty@test.invalid")))
      (is (not (s/valid? ::ys/email "kulma<sulku@test.invalid")))
      (is (not (s/valid? ::ys/email "watch\u231Aemoji@test.invalid")))
      (is (not (s/valid? ::ys/email ".starts.with.a.period@test.invalid")))
      (is (not (s/valid? ::ys/email "ends.with.a.period.@test.invalid")))
      (is (not (s/valid? ::ys/email "consecutive..periods@test.invalid")))
      (is (not (s/valid? ::ys/email "asd@sd.com@<script>i++</script>"))))
    (testing "domain part consists of at least two syntactically valid subdomains"
      (is (s/valid? ::ys/email "foo@bar.dev"))
      (is (s/valid? ::ys/email "foo@bar1-2.dev03"))
      (is (not (s/valid? ::ys/email "foo@bar")))
      (is (not (s/valid? ::ys/email "foo@bar.")))
      (is (not (s/valid? ::ys/email "foo@bar.%")))
      (let [too-long-subdomain (repeat 64 "x")]
        (is (not (s/valid? ::ys/email (str "foo@bar." too-long-subdomain ".dev"))))))))
