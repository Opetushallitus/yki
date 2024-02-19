(ns yki.middleware.error-boundary-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as jio]
    [clojure.test :refer [deftest is testing]]
    [compojure.api.sweet :refer [api context GET]]
    [integrant.core :as ig]
    [ring.mock.request :refer [query-string request]]
    [ring.util.http-response :refer [ok]]
    [yki.middleware.error-boundary :as error-boundary]
    [yki.spec :as ys]))

(defn- response->data [response]
  (with-open [rdr (jio/reader (:body response))]
    (json/read rdr {:key-fn keyword})))

(defn- create-handler [route]
  (api
    (context "" []
      :coercion :spec
      :middleware [(ig/init-key ::error-boundary/with-error-handling {})]
      route)))

(deftest error-handling-test
  (testing "request validation errors are classified properly"
    (let [handler  (create-handler
                     (GET "/" _
                       :query-params [{from :- ::ys/date-type nil}]
                       (ok {:foo :bar})))
          response (-> (request :get "/")
                       (query-string {:from "invalid-date-str"})
                       (handler))
          {trace-id   :trace-id
           error-type :error-type} (response->data response)]
      (is (= 400 (:status response)))
      (is (uuid? (parse-uuid trace-id)))
      (is (= "invalid-request" error-type))))
  (testing "response validation errors are classified properly"
    (let [handler  (create-handler
                     (GET "/" _
                       :return ::ys/response
                       (ok {:foo :bar})))
          response (-> (request :get "/")
                       (handler))
          {trace-id   :trace-id
           error-type :error-type} (response->data response)]
      (is (= 500 (:status response)))
      (is (uuid? (parse-uuid trace-id)))
      (is (= "invalid-response" error-type))))
  (testing "other errors are handled as well"
    (let [handler  (create-handler
                     (GET "/" _
                       (throw (ex-info "Error!" {}))))
          response (-> (request :get "/")
                       (handler))
          {trace-id   :trace-id
           error-type :error-type} (response->data response)]
      (is (= 400 (:status response)))
      (is (uuid? (parse-uuid trace-id)))
      (is (= "unknown" error-type))))
  (testing "successful responses are returned unchanged"
    (let [response-data {:foo :bar}
          handler       (create-handler
                          (GET "/" _
                            (ok response-data)))
          response      (-> (request :get "/")
                            (handler))]
      (is (= 200 (:status response)))
      (is (= response-data (-> response
                               (response->data)
                               (update-vals keyword)))))))
