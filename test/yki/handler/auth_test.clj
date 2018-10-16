(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [stub-http.core :refer :all]
            [yki.util.url-helper]
            [yki.handler.auth]
            [yki.boundary.onr]
            [jsonista.core :as json]
            [yki.middleware.auth]
            [yki.boundary.cas :as cas]
            [yki.handler.base-test :as base]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [yki.handler.routing :as routing]))

(defn- get-mock-routes [port]
  (merge
   {"/oppijanumerorekisteri-service/henkilo/hetu=090940-9224" {:status 200 :content-type "application/json"
                                                               :body (slurp "test/resources/onr_henkilo_by_hetu.json")}
    "/oppijanumerorekisteri-service/henkilo/hetu=260553-959D" {:status 404}
    "/oppijanumerorekisteri-service/j_spring_cas_security_check" {:status 200
                                                                  :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}}}
   (base/cas-mock-routes port)))

(defn- create-routes [port]
  (let [uri (str "localhost:" port)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri
                                                      :yki-host uri
                                                      :liiteri-host uri
                                                      :host-tunnistus uri
                                                      :scheme "http"})
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:url-helper url-helper
                           :session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :path "/yki"}}})
        cas-client (ig/init-key  :yki.boundary.cas/cas-client {:url-helper url-helper
                                                               :cas-creds {:username "username"
                                                                           :password "password"}})
        onr-client (ig/init-key :yki.boundary.onr/onr-client {:url-helper url-helper
                                                              :cas-client cas-client})
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                             :url-helper url-helper
                                                                             :permissions-client {}
                                                                             :onr-client onr-client
                                                                             :cas-client cas-client}))]
    auth-handler))

(deftest redirect-unauthenticated-user-to-authentication-test
  (let [handler (create-routes 8080)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/auth-root
                                      :request-method :get))]
    (testing "unauthenticated user should be redirected with http code 303"
      (is (= (get-in response [:response :status]) 303))
      (is (= ((get-in response [:response :headers]) "Location") "https:///shibboleth/ykiLoginFI")))))

(def user-1 {"lastname" "Aakula"
            "nickname" "Emma"
            "ssn" "090940-9224"
            "post-office" ""
            "external-user-id" "1.2.246.562.24.81121191558"
            "street-address" ""
            "firstname" "Emma"
            "zip" ""})

(def user-2 {"lastname" "Parkkonen-Testi"
           "nickname" nil
           "ssn" "260553-959D"
           "post-office" ""
           "external-user-id" nil
           "street-address" "Ateläniitynpolku 29 G"
           "firstname" "Carl-Erik"
           "zip" ""})

(deftest init-session-data-from-headers-and-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                        :headers (json/read-value (slurp "test/resources/headers.json"))
                                        :request-method :get)
                       (peridot/follow-redirect))
          response-body (base/body-as-json (:response response))
          identity (get-in response-body ["session" "identity"])]
      (testing "after init session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= identity user-1))))))

(deftest init-session-data-person-not-found-from-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                        :headers (json/read-value (slurp "test/resources/headers2.json"))
                                        :request-method :get)
                       (peridot/follow-redirect))
          response-body (base/body-as-json (:response response))
          identity (get-in response-body ["session" "identity"])]
      (testing "after init session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= identity user-2))))))
