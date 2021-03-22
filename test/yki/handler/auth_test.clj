(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [stub-http.core :refer :all]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clojure.string :as s]
            [duct.database.sql :as sql]
            [yki.util.url-helper]
            [yki.handler.auth]
            [yki.boundary.onr]
            [yki.embedded-db :as embedded-db]
            [clojure.java.jdbc :as jdbc]
            [jsonista.core :as json]
            [yki.handler.login-link :as login-link]
            [yki.middleware.auth]
            [yki.boundary.cas :as cas]
            [yki.handler.base-test :as base]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- get-mock-routes [port]
  (merge
   {"/oppijanumerorekisteri-service/henkilo/hetu=090940-9224" {:status 200 :content-type "application/json"
                                                               :body (slurp "test/resources/onr_henkilo_by_hetu.json")}
    "/oppijanumerorekisteri-service/henkilo/hetu=260553-959D" {:status 404}}
   (base/cas-mock-routes port)))

(defn- create-routes [port]
  (let [uri (str "localhost:" port)
        url-helper (base/create-url-helper uri)
        db (sql/->Boundary @embedded-db/conn)
        auth (base/auth url-helper)
        auth-handler (base/auth-handler auth url-helper)]
    auth-handler))

(deftest redirect-unauthenticated-user-to-authentication-test
  (let [handler (create-routes 8080)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/auth-root
                                      :request-method :get))]
    (testing "unauthenticated user should be redirected with http code 303"
      (is (= (get-in response [:response :status]) 303))
      (is (= ((get-in response [:response :headers]) "Location") "https://localhost:8080/cas-oppija/login?service=http://yki.localhost:8080/yki/ilmoittautuminen/tutkintotilaisuus/$1?lang=$2")))))

(def user-1 {"last_name" "Aakula"
             "nick_name" "Emma"
             "ssn" "090940-9224"
             "post_office" ""
             "oid" "1.2.246.562.24.81121191558"
             "external-user-id" "1.2.246.562.24.81121191558"
             "nationalities" ["246"],
             "street_address" ""
             "first_name" "Emma"
             "zip" ""})

(def user-2 {"last_name" "Parkkonen-Testi"
             "nick_name" nil
             "ssn" "260553-959D"
             "post_office" ""
             "oid" nil
             "external-user-id" "260553-959D"
             "nationalities" [],
             "street_address" "Ateläniitynpolku 29 G"
             "first_name" "Carl-Erik"
             "zip" ""})

(def code-expired "4ce84260-3d04-445e-b914-38e93c1ef668")
(def code-not-found "4ce84260-3d04-445e-b914-38e93c1ef698")

(deftest init-session-data-from-headers-and-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request (str routing/auth-root "?examSessionId=1&lang=fi"))
                       (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                        :headers (json/read-value (slurp "test/resources/headers.json"))
                                        :request-method :get)
                       (peridot/request (str routing/auth-root "/user")))
          response-body (base/body-as-json (:response response))
          identity (response-body "identity")]
      (testing "after init session session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= identity user-1))))))

(deftest init-session-data-person-not-found-from-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          redirect-response (-> session
                                (peridot/request (str routing/auth-root "?examSessionId=1"))
                                (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                                 :headers (json/read-value (slurp "test/resources/headers2.json" :encoding "ISO-8859-1"))))
          response (-> redirect-response
                       (peridot/request (str routing/auth-root "/user")))
          response-body (base/body-as-json (:response response))
          id (response-body "identity")]
      (testing "after authentication should redirect to exam session page"
        (is (s/includes? ((get-in redirect-response [:response :headers]) "Location")
                         "ilmoittautuminen/tutkintotilaisuus/1?lang=fi")))
      (testing "after init session session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= id user-2))))))

(deftest login-and-logout-with-login-link-test
  (base/insert-base-data)
  (base/insert-login-link base/code-ok "2038-01-01")
  (base/insert-login-link code-expired (l/format-local-time (l/local-now) :date))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/login?code=" base/code-ok))
                     (peridot/request (str routing/auth-root "/user")))
        response-body (base/body-as-json (:response response))
        id (response-body "identity")
        logout-response (-> response
                            (peridot/request (str routing/auth-root "/logout"))
                            (peridot/request (str routing/auth-root "/user")))
        logout-response-body (base/body-as-json (:response logout-response))]
    (testing "after successfull login link authentication session should contain user data"
      (is (= (get-in response [:response :status]) 200))
      (is (= (id "external-user-id") "test@user.com")))
    (testing "after logout session should not contain user data"
      (is (= (get-in logout-response [:response :status]) 200))
      (is (= (logout-response-body "identity") nil))))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/login?code=" code-expired)))]
    (testing "when trying to login with expired code should redirect to expired url"
      (is (= (get-in response [:response :status]) 302))
      (is (= (get-in response [:response :headers]) {"Location" "http://localhost/expired"}))))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/login?code=" "NOT_FOUND")))]
    (testing "when trying to login with non existing code should return 401"
      (is (= (get-in response [:response :status]) 401)))))
