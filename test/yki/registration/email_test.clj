(ns yki.registration.email-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [buddy.core.codecs :refer [bytes->hex]]
            [buddy.core.hash :refer [sha256]]
            [pgqueue.core :as pgq]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.registration.email :refer [exam-payment-receipt-bytes send-exam-registration-completed-email!]]
            [yki.util.common :refer [string->date]]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)

(defn- pdf-without-header-bytes [^bytes pdf-bytes]
  (let [total-bytes          (alength pdf-bytes)
        ; 256 is a somewhat arbitrary number, chosen to ensure that the
        ; /CreationDate (...) comment line is not included when comparing
        ; PDF contents, allowing for repeatable hashes.
        header-bytes-to-skip 256
        bytes-to-copy        (- total-bytes header-bytes-to-skip)
        dest                 (byte-array bytes-to-copy)]
    (System/arraycopy pdf-bytes header-bytes-to-skip dest 0 bytes-to-copy)
    dest))

(deftest exam-registration-confirmation-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}
    (let [url-helper        (base/create-url-helper (str "localhost:" port))
          email-q           (base/email-q)
          registration-data {:level_code     "KESKI"
                             :language_code  "fin"
                             :email          "teijo@test.invalid"
                             :last_name      "Testitapaus"
                             :first_name     "Teijo Antero"
                             :exam_date      (string->date "2022-12-10")
                             :name           "Testikouluttaja"
                             :street_address "Katukatu 1313 W 9"
                             :zip            "00100"
                             :post_office    "HELSINKI"}
          payment-data      {:paid_at (string->date "2022-10-13T22:00.00Z")
                             :amount  14000M
                             :id      199}]
      (send-exam-registration-completed-email! email-q url-helper "fi" registration-data payment-data)
      (let [{:keys [recipients attachments]} (pgq/take email-q)]
        (testing "Confirmation email is sent to correct recipient"
          (is (= ["teijo@test.invalid"] recipients)))
        (testing "Attachments contains a PDF receipt"
          (is (= 1 (count attachments)))
          (let [attachment-data             (first attachments)
                pdf-bytes                   (:data attachment-data)
                pdf-bytes-without-timestamp (pdf-without-header-bytes pdf-bytes)]
            (testing "Attachment has proper content type"
              (is (= "application/pdf" (:contentType attachment-data))))
            (testing "Attachment name contains payment ID"
              (is (= "kuitti_YKI-EXAM-199.pdf" (:name attachment-data))))
            (testing "Attachment contents has expected hash"
              (is (= (->> (assoc payment-data :receipt_id (str "YKI-EXAM-" (:id payment-data)))
                          (exam-payment-receipt-bytes url-helper "fi" registration-data)
                          (pdf-without-header-bytes)
                          (sha256)
                          (bytes->hex))
                     (-> pdf-bytes-without-timestamp
                         (sha256)
                         (bytes->hex)))))
            (testing "Attachment size is roughly as expected"
              (is (<= 20000 (alength pdf-bytes) 25000)))))))))
