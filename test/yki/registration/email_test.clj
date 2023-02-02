(ns yki.registration.email-test
  (:require
    [clojure.test :refer [deftest testing is use-fixtures]]
    [clj-time.core :as t]
    [pgqueue.core :as pgq]
    [selmer.parser :as parser]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.registration.email :refer [send-exam-registration-completed-email!]]
    [yki.util.common :refer [string->date]]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)

(deftest exam-registration-confirmation-test
  (let [email-q           (base/email-q)
        pdf-renderer      (base/mock-pdf-renderer)
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
        random-uuid       #uuid"f5e96975-9d1d-484e-a83b-deadec01ab7c"
        payment-data      {:paid_at   (string->date "2022-10-13T22:00.00Z")
                           :amount    14000M
                           :reference (str "YKI-EXAM-1-2-3-" random-uuid)}]
    (send-exam-registration-completed-email! email-q pdf-renderer "fi" registration-data payment-data)
    (let [{:keys [recipients attachments]} (pgq/take email-q)]
      (testing "Confirmation email is sent to correct recipient"
        (is (= ["teijo@test.invalid"] recipients)))
      (testing "Attachments contains a PDF receipt"
        (is (= 1 (count attachments)))
        (let [attachment-data     (first attachments)
              attachment-contents (:data attachment-data)]
          (testing "Attachment has proper content type"
            (is (= "application/pdf" (:contentType attachment-data))))
          (testing "Attachment name contains payment ID"
            (is (= (str (:reference payment-data) ".pdf") (:name attachment-data))))
          (testing "Attachment contents matches expectation"
            (is (= attachment-contents
                   (parser/render-file "exam_payment_receipt_template.html" {:current_date (t/now)})))))))))
