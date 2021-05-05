(ns yki.handler.evaluation-payment-test
  (:require [clojure.test :refer :all]
            [compojure.api.sweet :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [peridot.core :as peridot]
            [duct.database.sql]
            [stub-http.core :refer :all]
            [jsonista.core :as j]
            [pgqueue.core :as pgq]
            [muuntaja.middleware :as middleware]
            [compojure.core :as core]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [jsonista.core :as j]
            [yki.handler.base-test :as base]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]
            [yki.handler.evaluation-payment]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- send-request [request port]
  (let [handler (api (ig/init-key :yki.handler/evaluation-payment {:db (base/db)
                                                                   :payment-config   {:paytrail-host  "https://payment.paytrail.com/e2"
                                                                                      :yki-payment-uri "http://localhost:8080/yki/evaluation-payment"
                                                                                      :merchant_id 12345
                                                                                      :merchant_secret "6pKF4jkv97zmqBJ3ZL8gUw5DfT2NMQ"
                                                                                      :amount {:READING "50.00"
                                                                                               :LISTENING "50.00"
                                                                                               :WRITING "50.00"
                                                                                               :SPEAKING "50.00"}}
                                                                   :url-helper (base/create-url-helper (str "localhost:" port))
                                                                   :email-q (base/email-q)}))]
    (handler request)))

(deftest handle-evaluation-payment
  (base/insert-base-data)
  (base/insert-evaluation-payment-data)
  (with-routes!
    {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                               :body (slurp "test/resources/localisation.json")}}
    (let [evaluation-order    (base/get-evaluation-order)
          evaluation-order-id (:id evaluation-order)
          request             (mock/request :get (str routing/evaluation-payment-root "/formdata?evaluation-order-id=" evaluation-order-id))
          response            (send-request request port)
          print-res           (println "Response: " response)
          assd                (println "Body: " (j/read-value (:body response)))
         ; response-body       (base/body-as-json (:response response))
          ]
      (testing "smoke test"
        (is (= 1 1)))
      (testing "evaluation payment form data endpoint should return payment url and formdata"
        (is (= (get-in response [:response :status]) 200))
        ;(is (= base/evaluation-payment-formdata-json response-body))
        ))))
