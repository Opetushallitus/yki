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
            [yki.handler.routing :as routing]))

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
  (peridot/request session (str routing/payment-v2-root "/" registration-id "/redirect?lang=" lang)))

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
      ; TODO The block below will attempt to create a new payment on Paytrail each time this test is run - reconsider?
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

#_(deftest payment-callback-success-test
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body   (slurp "test/resources/localisation.json")}}
    ; TODO
    ; 1. Call with faulty parameters -> error, registration state doesn't change
    ; 2. Call with correct params -> ok, registration state changes
    ; 3. Call with different sequences of correct params, faulty params and error callback
    ;    -> registration state should stay at COMPLETED after first successful call
    ))

#_(deftest payment-callback-error-test
  '...)
