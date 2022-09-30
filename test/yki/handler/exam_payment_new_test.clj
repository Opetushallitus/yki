(ns yki.handler.exam-payment-new-test
  (:require
    [clojure.data.json :refer [read-str]]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures testing]]
    [compojure.core :as core]
    [duct.database.sql :as sql]
    [integrant.core :as ig]
    [muuntaja.middleware :as middleware]
    [peridot.core :as peridot]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.exam-payment-new]
    [yki.handler.routing :as routing]
    [yki.util.paytrail-payments :refer [sign-request]]))

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-login-link base/code-ok "2038-01-01")
  (f))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction insert-prereq-data)

(defn create-handlers
  [port]
  (let [use-new-payments-api?    true
        db                       (sql/->Boundary @embedded-db/conn)
        url-helper               (base/create-url-helper (str "localhost:" port))
        payment-helper           (base/create-examination-payment-helper db url-helper use-new-payments-api?)
        auth                     (base/auth url-helper)
        access-log               (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        auth-handler             (base/auth-handler auth url-helper)
        exam-payment-new-handler (middleware/wrap-format
                                   (ig/init-key :yki.handler/exam-payment-new {:access-log     access-log
                                                                               :auth           auth
                                                                               :db             db
                                                                               :email-q        (base/email-q)
                                                                               :url-helper     url-helper
                                                                               :payment-helper payment-helper}))]
    (core/routes exam-payment-new-handler auth-handler)))

(defn- redirect [session registration-id lang]
  (->> (str routing/payment-v2-root "/" registration-id "/redirect?lang=" lang)
       (peridot/request session)))

(defn- response->status [response]
  (:status (:response response)))

(defn- response->body [response]
  (-> (:response response)
      (base/body)
      (read-str {:key-fn keyword})))

(defn- select-payments-for-registration [registration-id]
  (base/select (str "SELECT * from exam_payment_new WHERE registration_id = " registration-id ";")))

(deftest paytrail-redirect-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}

    (let [handler         (create-handlers port)
          registration-id 1
          lang            "fi"]
      (testing "Calling redirect url without correct session details yields an error"
        (let [session           (peridot/session handler)
              redirect-response (-> session
                                    (redirect registration-id lang))]
          (is (= 401 (response->status redirect-response)))))
      (testing "Calling redirect url with correct session initiates payment and returns correct redirect URL"
        (is (empty? (select-payments-for-registration registration-id)))
        (let [session               (-> (peridot/session handler)
                                        (base/login-with-login-link))
              redirect-response     (-> session
                                        (redirect registration-id lang))
              payments              (select-payments-for-registration registration-id)
              expected-redirect-url (-> payments first :href)]
          (is (= 200 (response->status redirect-response)))
          (is (= 1 (count payments)))
          (is (= {:redirect expected-redirect-url} (response->body redirect-response))))))))

(defn- success [session lang query-params]
  (let [success-endpoint-url (str routing/paytrail-payment-root "/" lang "/success")
        query-params-str     (->> (for [[k v] query-params]
                                    (str k "=" v))
                                  (str/join "&"))]
    (->> (str success-endpoint-url "?" query-params-str)
         (peridot/request session))))

(deftest payment-success-handler-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}
    (let [handlers             (create-handlers port)
          registration-id      1
          amount               1337
          reference            "YKI-EXAM-testitesti123"
          transaction-id       "fake-paytrail-transaction-id"
          href                 (str "https://pay.paytrail.com/pay/" transaction-id)
          lang                 "fi"
          session              (peridot/session handlers)
          with-signature       (fn [headers]
                                 (->> (sign-request {:merchant-secret "SAIPPUAKAUPPIAS"} headers nil)
                                      (assoc headers "signature")))
          select-registration  #(base/select-one (str "SELECT * FROM registration WHERE id = " registration-id ";"))
          select-payment       #(first (select-payments-for-registration registration-id))
          request-with-headers (fn [request-headers]
                                 (success session lang request-headers))
          unauthorized?        (fn [{:keys [response]}]
                                 (= 401 (:status response)))
          error-redirect?      (fn [{:keys [response]}]
                                 (and (= 302 (:status response))
                                      (re-matches #".*status=payment-error.*" (get-in response [:headers "Location"]))))
          success-redirect?    (fn [{:keys [response]}]
                                 (and (= 302 (:status response))
                                      (str/ends-with? (get-in response [:headers "Location"]) "status=payment-success&lang=fi&id=1")))]
      (base/insert-exam-payment-new registration-id amount reference transaction-id href)
      (testing "Invoking success callback with missing or incorrect signature yields 401 Unauthorized"
        (is (unauthorized? (request-with-headers {})))
        (is (unauthorized? (request-with-headers {"signature" "incorrect-signature"}))))
      (let [correct-headers {"checkout-amount"         amount
                             "checkout-transaction-id" transaction-id
                             "checkout-status"         "ok"}]
        (testing "When success callback is invoked with incorrect parameters, the payment and registration states remain unchanged."
          ; Only signature passed in headers -> status code 400 expected
          (is (error-redirect? (request-with-headers (with-signature {}))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching amount -> error
          (is (error-redirect? (->> (update correct-headers "checkout-amount" inc)
                                    (with-signature)
                                    (request-with-headers))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching transaction-id -> error
          (is (error-redirect? (->> (assoc correct-headers "checkout-transaction-id" inc)
                                    (with-signature)
                                    (request-with-headers))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching status -> error
          (is (error-redirect? (->> (assoc correct-headers "checkout-status" "error")
                                    (with-signature)
                                    (request-with-headers))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment)))))

        (testing "Payment and registration status are updated when callback is invoked with correct parameters"
          (is (success-redirect? (->> correct-headers
                                      (with-signature)
                                      (request-with-headers))))
          (is (= "COMPLETED" (:state (select-registration))))
          (is (= "PAID" (:state (select-payment)))))

        (testing "Further calls to success callback do not change payment or registration status"
          ; Endpoint called with unexpected value for checkout-status header
          ; => error, but registration and payment statuses are unchanged
          (is (error-redirect? (->> (assoc correct-headers "checkout-status" "error")
                                    (with-signature)
                                    (request-with-headers))))
          (is (= "COMPLETED" (:state (select-registration))))
          (is (= "PAID" (:state (select-payment)))))
        ; Endpoint called again with correct parameters
        ; => redirect, registration and payment status remain unchanged
        (is (success-redirect? (->> correct-headers
                                    (with-signature)
                                    (request-with-headers))))
        (is (= "COMPLETED" (:state (select-registration))))
        (is (= "PAID" (:state (select-payment))))))))

