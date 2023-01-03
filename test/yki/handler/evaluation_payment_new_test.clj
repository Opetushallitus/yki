(ns yki.handler.evaluation-payment-new-test
  (:require
    [clojure.test :refer [deftest use-fixtures is testing]]
    [clojure.string :as str]
    [clj-time.core :as t]
    [compojure.api.sweet :refer [api]]
    [duct.database.sql]
    [integrant.core :as ig]
    [pgqueue.core :as pgq]
    [ring.mock.request :as mock]
    [selmer.parser :as parser]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]
    [yki.handler.evaluation-payment-new]
    [yki.util.common :refer [format-date-string-to-finnish-format]]
    [yki.util.paytrail-payments :refer [amount->paytrail-amount sign-request sign-string]]))

(def test-order {:first_names "Anne Marie"
                 :last_name   "Jones"
                 :email       "anne-marie.jones@testi.fi"
                 :birthdate   "2000-02-14"})

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-evaluation-data)
  (let [{evaluation-order-id :evaluation-order-id} (base/insert-evaluation-order-data test-order)]
    (base/insert-evaluation-payment-new-data evaluation-order-id))
  (f))

(defn- create-handler [port]
  (let [url-helper (base/create-url-helper (str "localhost:" port))
        db         (base/db)]
    (api (ig/init-key
           :yki.handler/evaluation-payment-new
           {:db             db
            :auth           (base/auth url-helper)
            :access-log     (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
            :payment-helper (base/create-evaluation-payment-helper db url-helper true)
            :pdf-renderer   (base/mock-pdf-renderer url-helper)
            :url-helper     url-helper
            :email-q        (base/email-q)}))))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction insert-prereq-data)

; Note: yki.handler.exam-payment-new-test creates actual test payments against the Paytrail API.
; This namespace instead mocks the API responses from Paytrail, thus concentrating on just testing the
; handler logic itself instead of the external service.

(defn redirect [handler evaluation-order-id signature]
  (let [redirect-route (str routing/evaluation-payment-new-root "/" evaluation-order-id "/redirect?signature=" signature)]
    (-> (mock/request :get redirect-route)
        (handler))))

(defn success [handler lang query-params]
  (let [query-params-str       (->> (for [[k v] query-params]
                                      (str k "=" v))
                                    (str/join "&"))
        success-callback-route (str/join "/" [routing/evaluation-payment-new-paytrail-callback-root lang "success"])]
    (-> (mock/request :get (str success-callback-route "?" query-params-str))
        (handler))))

(defn error-redirect? [response]
  (and (= 302 (:status response))
       (re-matches #".*status=payment-error.*" (get-in response [:headers "Location"]))))

(defn success-redirect? [response]
  (and (= 302 (:status response))
       (re-matches #".*status=payment-success.*" (get-in response [:headers "Location"]))))

(defn response->status [response]
  (:status response))

(defn order-id->payment-data [evaluation-order-id]
  (base/select-one
    (str "SELECT epn.amount, epn.evaluation_order_id, epn.state, epn.href, epn.transaction_id FROM evaluation_payment_new epn WHERE epn.evaluation_order_id = "
         evaluation-order-id ";")))

(deftest paytrail-redirect-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}

    (let [handler             (create-handler port)
          evaluation-order-id 1
          payment-data        (order-id->payment-data evaluation-order-id)
          expected-signature  (sign-string base/new-evaluation-payment-config (str evaluation-order-id))]
      (testing "Calling redirect url without signature yields 400 Bad Request"
        (is (= 400 (-> (redirect handler 1 nil)
                       (response->status))))
        (is (= 400 (-> (redirect handler 1 "")
                       (response->status)))))
      (testing "Calling redirect url with incorrect signature yields 401 Unauthorized"
        (is (= 401 (-> (redirect handler 1 "foobar")
                       (response->status))))
        (is (= 401 (-> (redirect handler 1 (subs expected-signature 1))
                       (response->status)))))
      (testing "Calling redirect url with correct signature redirects user to Paytrail"
        (let [response (redirect handler 1 expected-signature)]
          (is (= 200 (-> response
                         (response->status))))
          (is (= (:href payment-data) (-> response
                                          (base/body-as-json)
                                          (get "redirect")))))))))

(defn without-empty-lines [text]
  (remove str/blank? (str/split-lines text)))

(deftest paytrail-callback-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}
    (let [handler                        (create-handler port)
          lang                           "fi"
          evaluation-order-id            1
          {amount         :amount
           transaction-id :transaction_id} (order-id->payment-data evaluation-order-id)
          with-signature                 (fn [headers]
                                           (->> (sign-request {:merchant-secret "SAIPPUAKAUPPIAS"} headers nil)
                                                (assoc headers "signature")))
          valid-success-callback-headers {"checkout-amount"         (amount->paytrail-amount amount)
                                          "checkout-transaction-id" transaction-id
                                          "checkout-status"         "ok"}
          get-payment-status             #(:state (order-id->payment-data evaluation-order-id))
          email-q                        (base/email-q)]
      (testing "Invoking success callback with missing or incorrect signature yields 401 Unauthorized"
        (is (= 401 (-> (success handler lang {})
                       (response->status))))
        (is (= 401 (-> (success handler lang valid-success-callback-headers)
                       (response->status))))
        (is (= 401 (-> (success handler lang (assoc valid-success-callback-headers "signature" "incorrect-signature"))
                       (response->status))))
        (is (= 401 (-> (success handler lang (update (with-signature valid-success-callback-headers) "signature" str/upper-case))
                       (response->status)))))
      (testing "Invoking success callback with correct signature but incorrect payment details does not change payment status"
        ; Wrong transaction id
        (is (error-redirect? (->> (update valid-success-callback-headers "checkout-transaction-id" str/upper-case)
                                  (with-signature)
                                  (success handler lang))))
        (is (= "UNPAID" (get-payment-status)))
        ; Wrong payment amount
        (is (error-redirect? (->> (update valid-success-callback-headers "checkout-amount" dec)
                                  (with-signature)
                                  (success handler lang))))
        (is (= "UNPAID" (get-payment-status)))
        ; Wrong payment status
        (is (error-redirect? (->> (assoc valid-success-callback-headers "checkout-status" "pending")
                                  (with-signature)
                                  (success handler lang))))
        (is (= "UNPAID" (get-payment-status))))
      (testing "Invoking success callback with correct signature and payment details changes payment status to PAID"
        (is (success-redirect? (->> (with-signature valid-success-callback-headers)
                                    (success handler lang))))
        (is (= "PAID" (get-payment-status))))
      (let [customer-email (pgq/take email-q)
            [receipt-attachment] (:attachments customer-email)
            kirjaamo-email (pgq/take email-q)
            exam-date      (base/two-weeks-ago)]
        (testing "Emails are sent to customer and kirjaamo after successful payment"
          (is (= {:recipients ["anne-marie.jones@testi.fi"]
                  :subject    (str/join ", " ["Tarkistusarviointi: suomi perustaso" (format-date-string-to-finnish-format exam-date)])}
                 (select-keys customer-email [:recipients :subject])))
          (testing "Customer email has PDF receipt as attachment"
            (is (= 1 (count (:attachments customer-email))))
            (is (= "application/pdf" (:contentType receipt-attachment)))
            (is (= "fake-reference.pdf" (:name receipt-attachment)))
            (testing "Attachment contents matches expectation"
              (is (= (without-empty-lines (:data receipt-attachment))
                     (without-empty-lines (parser/render-file "evaluation_payment_receipt_template.html" {:current_date (t/now)
                                                                                                          :exam_date    exam-date}))))))
          (is (= {:recipients ["kirjaamo@oph.fi"]
                  :subject    (str/join ", " ["YKI" "suomi perustaso" (format-date-string-to-finnish-format exam-date)])}
                 (select-keys kirjaamo-email [:recipients :subject])))))
      (testing "Once payment is marked as PAID, later callback invocations do not change the status"
        (is (error-redirect? (->> (assoc valid-success-callback-headers "checkout-status" "fail")
                                  (with-signature)
                                  (success handler lang))))
        (is (= "PAID" (get-payment-status))))
      (testing "Emails to customer and kirjaamo are NOT sent after further success callbacks"
        (is (nil? (pgq/take email-q)))))))
