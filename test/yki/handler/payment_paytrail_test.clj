(ns yki.handler.payment-paytrail-test
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [clojure.data.json :refer [read-json]]
            [compojure.core :as core]
            [duct.database.sql :as sql]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.payment-paytrail]
            [yki.handler.routing :as routing]
            [yki.util.payments-api :refer [sign-request]]
            [clojure.string :as str]))

(defn insert-prereq-data [f]
  (base/insert-base-data)
  (base/insert-registrations "SUBMITTED")
  (base/insert-login-link base/code-ok "2038-01-01")
  (f))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction insert-prereq-data)

(defn create-handlers
  [port]
  (let [use-new-payments-api?    true
        db                       (sql/->Boundary @embedded-db/conn)
        url-helper               (base/create-url-helper (str "localhost:" port))
        payment-helper           (base/create-payment-helper db url-helper use-new-payments-api?)
        auth                     (base/auth url-helper)
        access-log               (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        auth-handler             (base/auth-handler auth url-helper)
        payment-paytrail-handler (middleware/wrap-format
                                   (ig/init-key :yki.handler/payment-paytrail {:access-log     access-log
                                                                               :auth           auth
                                                                               :db             db
                                                                               :email-q        (base/email-q)
                                                                               :url-helper     url-helper
                                                                               :payment-helper payment-helper}))]
    (core/routes payment-paytrail-handler auth-handler)))

(defn- redirect [session registration-id lang]
  (->> (str routing/payment-v2-root "/" registration-id "/redirect?lang=" lang)
       (peridot/request session)))

(defn- response->status+body [response]
  (let [response (:response response)
        status   (:status response)
        body     (-> (base/body response)
                     (read-json))]
    {:body   body
     :status status}))

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
                                    (redirect registration-id lang))
              {status :status} (response->status+body redirect-response)]
          (is (= 400 status))))
      ; TODO The block below will attempt to create a new payment on Paytrail each time this test is run. Reconsider?
      (testing "Calling redirect url with correct session initiates payment and returns correct redirect URL"
        (is (empty? (select-payments-for-registration registration-id)))
        (let [session               (-> (peridot/session handler)
                                        (base/login-with-login-link))
              redirect-response     (-> session
                                        (redirect registration-id lang))
              {status :status
               body   :body} (response->status+body redirect-response)
              payments              (select-payments-for-registration registration-id)
              expected-redirect-url (-> payments first :href)]
          (is (= 200 status))
          (is (= 1 (count payments)))
          (is (= {:redirect expected-redirect-url} body)))))))

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
    (let [handlers            (create-handlers port)
          registration-id     1
          amount              1337
          reference           "YKI-EXAM-testitesti123"
          transaction-id      "fake-paytrail-transaction-id"
          href                (str "https://pay.paytrail.com/pay/" transaction-id)
          lang                "fi"
          session             (peridot/session handlers)
          with-signature      (fn [headers]
                                (->> (sign-request headers nil)
                                     (assoc headers "signature")))
          select-registration #(base/select-one (str "SELECT * FROM registration WHERE id = " registration-id ";"))
          select-payment      #(first (select-payments-for-registration registration-id))
          request->status     (fn [request-headers]
                                (-> (success session lang request-headers)
                                    (:response)
                                    (:status)))]
      (base/insert-exam-payment-new registration-id amount reference transaction-id href)
      (testing "Invoking success callback with missing or incorrect signature yields 401 Unauthorized"
        (is (= 401 (request->status {})))
        (is (= 401 (request->status {"signature" "incorrect-signature"}))))
      (let [correct-headers {"checkout-amount"         amount
                             "checkout-transaction-id" transaction-id
                             "checkout-status"         "ok"}]
        (testing "When success callback is invoked with incorrect parameters, the payment and registration states remain unchanged."
          ; Only signature passed in headers -> status code 400 expected
          (is (= 400 (request->status (with-signature {}))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching amount -> error
          (is (= 400 (->> (update correct-headers "checkout-amount" inc)
                          (with-signature)
                          (request->status))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching transaction-id -> error
          (is (= 400 (->> (assoc correct-headers "checkout-transaction-id" inc)
                          (with-signature)
                          (request->status))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment))))
          ; Mismatching status -> error
          (is (= 400 (->> (assoc correct-headers "checkout-status" "error")
                          (with-signature)
                          (request->status))))
          (is (= "SUBMITTED" (:state (select-registration))))
          (is (= "UNPAID" (:state (select-payment)))))

        (testing "Payment and registration status are updated when callback is invoked with correct parameters"
          (is (= 302 (->> correct-headers
                          (with-signature)
                          (request->status))))
          (is (= "COMPLETED" (:state (select-registration))))
          (is (= "PAID" (:state (select-payment)))))

        (testing "Further calls to success callback do not change payment or registration status"
          ; Endpoint called with unexpected value for checkout-status header
          ; => error, but registration and payment statuses are unchanged
          (is (= 400 (->> (assoc correct-headers "checkout-status" "error")
                          (with-signature)
                          (request->status))))
          (is (= "COMPLETED" (:state (select-registration))))
          (is (= "PAID" (:state (select-payment)))))
        ; Endpoint called again with correct parameters
        ; => redirect, registration and payment status remain unchanged
        (is (= 302 (->> correct-headers
                        (with-signature)
                        (request->status))))
        (is (= "COMPLETED" (:state (select-registration))))
        (is (= "PAID" (:state (select-payment))))))))

