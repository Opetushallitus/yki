(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [stub-http.core :refer :all]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [yki.util.url-helper]
            [yki.handler.auth]
            [yki.boundary.onr]
            [yki.embedded-db :as embedded-db]
            [clojure.java.jdbc :as jdbc]
            [jsonista.core :as json]
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
    "/oppijanumerorekisteri-service/henkilo/hetu=260553-959D" {:status 404}
    "/oppijanumerorekisteri-service/j_spring_cas_security_check" {:status 200
                                                                  :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}}}
   (base/cas-mock-routes port)))

(defn- create-routes [port]
  (let [uri (str "localhost:" port)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri
                                                      :oppija-host ""
                                                      :yki-host-virkailija uri
                                                      :alb-host (str "http://" uri)
                                                      :host-tunnistus uri
                                                      :scheme "http"})
        access-log (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        db (duct.database.sql/->Boundary @embedded-db/conn)
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
                                                                             :db db
                                                                             :url-helper url-helper
                                                                             :access-log access-log
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
(def code-ok "4ce84260-3d04-445e-b914-38e93c1ef667")
(def code-expired "4ce84260-3d04-445e-b914-38e93c1ef668")
(def code-not-found "4ce84260-3d04-445e-b914-38e93c1ef698")

(defn insert-login-link [code expires-at]
  (jdbc/execute! @embedded-db/conn (str "INSERT INTO login_link
        (code, participant_id, exam_session_id, expires_at, expired_link_redirect, success_redirect)
          VALUES ('" code "', 1, 1, '" expires-at "', 'http://localhost/expired', 'http://localhost/success' )")))

(deftest init-session-data-from-headers-and-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request (str routing/auth-root "?success-redirect=/yki/auth/user"))
                       (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                        :headers (json/read-value (slurp "test/resources/headers.json"))
                                        :request-method :get)
                       (peridot/follow-redirect))
          response-body (base/body-as-json (:response response))
          identity (get-in response-body ["session" "identity"])]
      (testing "after init session session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= identity user-1))))))

(deftest init-session-data-person-not-found-from-onr-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server)))
    (let [handler (create-routes port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request (str routing/auth-root "?success-redirect=/yki/auth/user"))
                       (peridot/request (str routing/auth-root routing/auth-init-session-uri)
                                        :headers (json/read-value (slurp "test/resources/headers2.json" :encoding "ISO-8859-1")))
                       (peridot/follow-redirect))
          response-body (base/body-as-json (:response response))
          identity (get-in response-body ["session" "identity"])]
      (testing "after init session session should contain user data"
        (is (= (get-in response [:response :status]) 200))
        (is (= identity user-2))))))

(deftest login-with-login-link-test
  (base/insert-login-link-prereqs)
  (insert-login-link code-ok "2038-01-01")
  (insert-login-link code-expired (l/format-local-time (l/local-now) :date))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/callback?code=" code-ok))
                     (peridot/request (str routing/auth-root "/user")))
        response-body (base/body-as-json (:response response))
        identity (get-in response-body ["session" "identity"])]
    (testing "after successfull login link authentication session should contain user data"
      (is (= (get-in response [:response :status]) 200))
      (is (= (identity "external-user-id") "test@user.com"))))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/callback?code=" code-expired)))]
    (testing "when trying to login with expired code should redirect to expired url"
      (is (= (get-in response [:response :status]) 302))
      (is (= (get-in response [:response :headers]) {"Location" "http://localhost/expired"}))))

  (let [handler (create-routes "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request (str routing/auth-root "/callback?code=" "NOT_FOUND")))]
    (testing "when trying to login with non existing code should return 401"
      (is (= (get-in response [:response :status]) 401)))))
