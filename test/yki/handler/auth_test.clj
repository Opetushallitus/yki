(ns yki.handler.auth-test
  (:require [clj-time.local :as l]
            [clojure.test :refer [deftest use-fixtures testing is]]
            [peridot.core :as peridot]
            [yki.boundary.onr]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- create-routes [port]
  (let [uri          (str "localhost:" port)
        url-helper   (base/create-url-helper uri)
        auth         (base/auth url-helper)
        auth-handler (base/auth-handler auth url-helper)]
    auth-handler))

(deftest redirect-unauthenticated-user-to-authentication-test
  (let [handler  (create-routes 8080)
        session  (peridot/session handler)
        response (-> session
                     (peridot/request routing/auth-root
                                      :request-method :get))]
    (testing "unauthenticated user should be redirected with http code 303"
      (is (= (get-in response [:response :status]) 303))
      (is (= ((get-in response [:response :headers]) "Location") "https://localhost:8080/cas-oppija/login?locale=fi&service=http://yki.localhost:8080/yki/auth/callbackFI?examSessionId=")))))

(def user-1 {"last_name"        "Aakula"
             "nick_name"        "Emma"
             "ssn"              "090940-9224"
             "post_office"      ""
             "oid"              "1.2.246.562.24.81121191558"
             "external-user-id" "1.2.246.562.24.81121191558"
             "nationalities"    ["246"]
             "street_address"   ""
             "first_name"       "Emma"
             "zip"              ""})

(def user-2 {"last_name"        "Parkkonen-Testi"
             "nick_name"        nil
             "ssn"              "260553-959D"
             "post_office"      ""
             "oid"              nil
             "external-user-id" "260553-959D"
             "nationalities"    []
             "street_address"   "Ateläniitynpolku 29 G"
             "first_name"       "Carl-Erik"
             "zip"              ""})

(def code-expired "4ce84260-3d04-445e-b914-38e93c1ef668")

(deftest login-and-logout-with-login-link-test
  (base/insert-base-data)
  (base/insert-login-link base/code-ok "2038-01-01")
  (base/insert-login-link code-expired (l/format-local-time (l/local-now) :date))

  (let [handler              (create-routes "")
        session              (peridot/session handler)
        response             (-> session
                                 (peridot/request (str routing/auth-root "/login?code=" base/code-ok))
                                 (peridot/request (str routing/auth-root "/user")))
        response-body        (base/body-as-json (:response response))
        id                   (response-body "identity")
        logout-response      (-> response
                                 (peridot/request (str routing/auth-root "/logout"))
                                 (peridot/request (str routing/auth-root "/user")))
        logout-response-body (base/body-as-json (:response logout-response))]
    (testing "after successfull login link authentication session should contain user data"
      (is (= (get-in response [:response :status]) 200))
      (is (= (id "external-user-id") "test@user.com")))
    (testing "after logout session should not contain user data"
      (is (= (get-in logout-response [:response :status]) 200))
      (is (= (logout-response-body "identity") nil))))

  (let [handler  (create-routes "")
        session  (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/login?code=" code-expired)))]
    (testing "when trying to login with expired code should redirect to expired url"
      (is (= (get-in response [:response :status]) 302))
      (is (= (get-in response [:response :headers]) {"Location" "http://localhost/expired"}))))

  (let [handler  (create-routes "")
        session  (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/login?code=" "NOT_FOUND")))]
    (testing "when trying to login with non existing code should return 401"
      (is (= (get-in response [:response :status]) 401)))))
