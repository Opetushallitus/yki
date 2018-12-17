(ns yki.handler.localisation-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [compojure.api.sweet :refer :all]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [stub-http.core :refer :all]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [yki.util.url-helper]
            [yki.handler.localisation]))

(def mock-route
  {"/lokalisointi/cxf/rest/v1/localisation" {:status 200
                                             :content-type "application/json"
                                             :body (slurp "test/resources/localisation.json")}})
(defn- create-route
  [port]
  (let [uri (str "localhost:" port)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri
                                                      :oppija-host uri
                                                      :yki-register-host uri
                                                      :yki-host-virkailija uri
                                                      :alb-host (str "http://" uri)
                                                      :scheme "http"})
        localisation-handler (api (ig/init-key :yki.handler/localisation {:url-helper url-helper}))]
    localisation-handler))

(deftest get-localisation-test
  (with-routes! mock-route
    (let [request (mock/request :get routing/localisation-api-root)
          response ((create-route port) request)
          response-body (base/body-as-json response)]
; (println response-body)
      (testing "translations"
        (is (= (response-body "email.login.subject") "Ilmoittautuminen"))))))

