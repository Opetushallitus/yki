(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [yki.handler.base-test :as base]
            [jsonista.core :as j]
            [compojure.core :refer :all]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.cas :as cas]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- get-mock-routes [port user]
  {"/kayttooikeus-service/kayttooikeus/kayttaja" {:status 200 :content-type "application/json"
                                                  :body (slurp (str "test/resources/" user ".json"))}
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

(defn- create-routes
  [port]
  (let [uri (str "localhost:" port)
        db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri :yki-host uri :liiteri-host uri :protocol-base "http"})
        auth (ig/init-key :yki.middleware.auth/with-authentication {:url-helper url-helper
                                                                    :session-config {:key "ad7tbRZIG839gDo2"
                                                                                     :cookie-attrs {:max-age 28800
                                                                                                    :http-only true
                                                                                                    :secure false
                                                                                                    :path "/yki"}}})
        cas-client (ig/init-key  :yki.boundary.cas/cas-client {:url-helper url-helper
                                                               :cas-creds {:username "username"
                                                                           :password "password"}})
        permissions-client (ig/init-key  :yki.boundary.permissions/permissions-client
                                         {:url-helper url-helper
                                          :cas-client cas-client})
        org-handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                                 :url-helper url-helper
                                                                                 :exam-session-handler {}
                                                                                 :auth auth
                                                                                 :file-handler {}}))
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                             :url-helper url-helper
                                                                             :permissions-client permissions-client
                                                                             :cas-client cas-client}))]
    (routes org-handler auth-handler)))

(defn- login [port]
  (let [routes (create-routes port)
        session (peridot/session routes)
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get
                                      :params {:ticket "ST-15126"}))]
    response))

(defn- fire-requests [port]
  (let [session (login port)
        organizer-post (-> session
                           (peridot/request routing/organizer-api-root
                                            :body (j/write-value-as-string base/organizer)
                                            :content-type "application/json"
                                            :request-method :post))
        organizer-put (-> session
                          (peridot/request (str routing/organizer-api-root "/1.2.3.4")
                                           :body (j/write-value-as-string base/organizer)
                                           :content-type "application/json"
                                           :request-method :put))
        organizer-delete (-> session
                             (peridot/request (str routing/organizer-api-root "/1.2.3.6")
                                              :request-method :delete))
        organizer-get (-> session
                          (peridot/request routing/organizer-api-root
                                           :request-method :get))]
    {:post organizer-post
     :put organizer-put
     :delete organizer-delete
     :get organizer-get}))

(deftest handle-authentication-success-callback-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "yki_user"))
    (let [handler (create-routes port)
          session (peridot/session (routes handler))
          response (-> session
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/follow-redirect))
          response-body (j/read-value (slurp (:body (:response response)) :encoding "UTF-8"))
          identity (get-in response-body ["session" "identity"])
          organizations (identity "organizations")]
      (testing "callback endpoint should set identity returned from cas client to session"
        (is (= (identity "username") "test")))
      (testing "callback endpoint should set only YKI permissions returned from permissions client to session"
        (is (= organizations
               [{"oid" "1.2.3.4" "permissions" [{"oikeus" "ADMIN" "palvelu" "YKI"}]}]))))))

(deftest handle-authentication-callback-without-ticket-test
  (let [handler (create-routes "")
        session (peridot/session (routes handler))
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get))]
    (testing "callback endpoint should return Unauthorized when callback doesn't include ticket"
      (is (= (get-in response [:response :status]) 401)))))

(deftest handle-logout-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "yki_user"))
    (let [handler (create-routes port)
          session (peridot/session (routes handler))
          response (-> session
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/request routing/virkailija-auth-logout
                                        :request-method :get))]
      (testing "logout endpoint redirects to cas logout"
        (is (= (get-in response [:response :status]) 302))))))

(deftest user-with-yki-permissions-authorization-test
  (base/insert-organizer "'1.2.3.4'")
  (base/insert-organizer "'1.2.3.5'")

  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "yki_user"))
    (let [responses (fire-requests port)
          organizers ((base/body-as-json (-> responses :get :response)) "organizers")]
      (testing "post should not be allowed"
        (is (= (-> responses :post :response :status) 401)))
      (testing "put should not be allowed"
        (is (= (-> responses :put :response :status) 401)))
      (testing "delete should not be allowed"
        (is (= (-> responses :delete :response :status) 401)))
      (testing "get should return only organizers that user has permissions to see"
        (is (= (count organizers) 1))
        (is (= (get (first organizers) "oid") "1.2.3.4"))))))

(deftest user-with-oph-permissions-authorization-test
  (base/insert-organizer "'1.2.3.5'")

  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "oph_user"))
    (let [responses (fire-requests port)
          organizers ((base/body-as-json (-> responses :get :response)) "organizers")]
      (testing "post should be allowed"
        (is (= (-> responses :post :response :status) 200)))
      (testing "put should be allowed"
        (is (= (-> responses :put :response :status) 200)))
      (testing "delete should be allowed"
        (is (= (-> responses :delete :response :status) 404)))
      (testing "get should return all organizers"
        (is (= (count organizers) 2))))))

(deftest user-without-yki-permissions-authorization-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "no_permissions_user"))
    (let [responses (fire-requests port)]
      (testing "post should not be allowed"
        (is (= (-> responses :post :response :status) 401)))
      (testing "put should not be allowed"
        (is (= (-> responses :put :response :status) 401)))
      (testing "delete should not be allowed"
        (is (= (-> responses :delete :response :status) 401)))
      (testing "get should not be allowed"
        (is (= (-> responses :get :response :status)
               401))))))

