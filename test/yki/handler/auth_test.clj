(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [jsonista.core :as j]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.cas :as cas]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]))

(defn get-routes [port]
  {"/kayttooikeus-service/kayttooikeus/kayttaja?username=test" {:status 200 :content-type "application/json"
                                                                :body (slurp "test/resources/user.json")}
   "/kayttooikeus-service/j_spring_cas_security_check" {:status 200
                                                        :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}}
   "/cas/serviceValidate" {:status 200 :content-type "application/xml;charset=UTF-8"
                           :body (slurp "test/resources/serviceResponse.xml")}
   "/cas/v1/tickets" {:status 201
                      :method :post
                      :headers {"Location" (str "http://localhost:" port "/cas/v1/tickets/TGT-1-FFDFHDSJK")}
                      :body "ST-1-FFDFHDSJK2"}
   "/cas/v1/tickets/TGT-1-FFDFHDSJK" {:status 200
                                      :method :post
                                      :body "ST-1-FFDFHDSJK2"}})

(defn- create-handler [port]
  (let [uri (str "localhost:" port)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri :yki-host uri :liiteri-host uri :protocol-base "http"})
        auth (ig/init-key :yki.middleware.auth/with-authentication {:url-helper url-helper})
        cas-client (ig/init-key  :yki.boundary.cas/cas-client {:url-helper url-helper
                                                               :cas-creds {:username "username"
                                                                           :password "password"}})
        permissions-client (ig/init-key  :yki.boundary.permissions/permissions-client
                                         {:url-helper url-helper
                                          :cas-client cas-client})
        handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                        :url-helper url-helper
                                                                        :permissions-client permissions-client
                                                                        :cas-client cas-client}))]
    handler))

(deftest handle-authentication-success-callback-test
  (with-routes!
    (fn [server]
      (get-routes (:port server)))
    (let [handler (create-handler port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/follow-redirect))
          response-body (j/read-value (slurp (:body (:response response)) :encoding "UTF-8"))
          identity (get-in response-body ["session" "identity"])
          permissions (get-in response-body ["session" "identity" "permissions"])]
      (testing "callback endpoint should set identity returned from cas client to session"
        (is (= (identity "username") "test")))
      (testing "callback endpoint should set YKI roles returned from permissions client to session"
        (is (= permissions
               [{"organisaatioOid" "1.2.3.4" "kayttooikeudet" [{"oikeus" "ADMIN" "palvelu" "YKI"}, {"oikeus" "ADMIN" "palvelu" "OTHER"}]}]))))))

(deftest handle-authentication-callback-without-ticket-test
  (let [handler (create-handler "")
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get))]
    (testing "callback endpoint should return Unauthorized when callback doesn't include ticket"
      (is (= (get-in response [:response :status]) 401)))))

(deftest handle-logout-test
  (with-routes!
    (fn [server]
      (get-routes (:port server)))
    (let [handler (create-handler port)
          session (peridot/session handler)
          response (-> session
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/request routing/virkailija-auth-logout
                                        :request-method :get))]
      (testing "logout endpoint redirects to cas logout"
        (is (= (get-in response [:response :status]) 302))))))

