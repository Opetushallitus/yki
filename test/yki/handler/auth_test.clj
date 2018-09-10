(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :refer :all]
            [peridot.core :as peridot]
            [duct.core :as duct]
            [yki.boundary.cas :as cas]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]))

(defrecord MockCasClient [url-helper]
  cas/CasAccess
  (validate-ticket [_ ticket]
    "username")
  (get-cas-session [this cas-params]
    "cas-session")
  (cas-authenticated-post [this url body]
    "cas-post")
  (cas-authenticated-get [this url]
    "cas-get"))

(defrecord MockPermissionsClient [url-helper cas-client]
  permissions/Permissions
  (virkailija-by-username [_ username]
    ""))

(defn- create-handler []
  (let [url-helper (ig/init-key :yki.util/url-helper {:virkailija-host "http://localhost:8080"
                                                      :yki-host "http://localhost:8080"})
        auth (ig/init-key :yki.middleware.auth/with-authentication {:url-helper url-helper})
        cas-client (fn [_]
                     (->MockCasClient url-helper))
        permissions-client (->MockPermissionsClient url-helper cas-client)
        handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                        :url-helper url-helper
                                                                        :permissions-client permissions-client
                                                                        :cas-client cas-client}))]
    handler))

(deftest handle-authentication-success-callback-test
  (let [handler (create-handler)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get
                                      :params {:ticket "ST-15126"})
                     (peridot/follow-redirect))
        response-body (parse-string (slurp (:body (:response response)) :encoding "UTF-8"))]
    (testing "callback endpoint should set identity returned from cas client to session"
      (is (= (get-in response-body ["session" "identity"]) {"username" "username"})))))

(deftest handle-authentication-callback-without-ticket-test
  (let [handler (create-handler)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get))]
    (testing "callback endpoint should return Unauthorized when callback doesn't include ticket"
      (is (= (get-in response [:response :status]) 401)))))

(deftest handle-logout-test
  (let [handler (create-handler)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/virkailija-auth-callback
                                      :request-method :get
                                      :params {:ticket "ST-15126"})
                     (peridot/request routing/virkailija-auth-logout
                                      :request-method :get))]
    (testing "logout endpoint redirects to cas logout"
      (is (= (get-in response [:response :status]) 302)))))

