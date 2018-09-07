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
            [yki.boundary.cas-access :as cas]
            [yki.embedded-db :as embedded-db]
            [yki.handler.routing :as routing]
            [yki.handler.auth]))

(use-fixtures :once (join-fixtures [embedded-db/with-postgres embedded-db/with-migration]))

(defrecord MockCasClient [url-helper]
  cas/CasAccess
  (validate-ticket [this ticket]
    "username"))

(defn mock-cas [url-helper]
  (->MockCasClient url-helper))

(defn- create-handler [tx]
  (let [url-helper (ig/init-key :yki.util/url-helper {:virkailija-host "http://localhost:8080"
                                                     :yki-host "http://localhost:8080"})
        db (duct.database.sql/->Boundary tx)
        auth (ig/init-key :yki.middleware.auth/with-authentication {:db db :url-helper url-helper})
        handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:db db :auth auth :url-helper url-helper :cas-access mock-cas}))]
    handler))

(deftest handle-authentication-success-callback-test
  (jdbc/with-db-connection [tx embedded-db/db-spec]
    (let [handler (create-handler tx)
          session (peridot/session handler)
          response (-> session
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/follow-redirect))
          response-body (parse-string (slurp (:body (:response response)) :encoding "UTF-8"))]
      (testing "callback endpoint should set identity returned from cas client to session"
        (is (= (get-in response-body ["session" "identity"]) {"username" "username"}))))))

(deftest handle-authentication-callback-without-ticket-test
  (jdbc/with-db-connection [tx embedded-db/db-spec]
    (let [handler (create-handler tx)
          session (peridot/session handler)
          response (-> session
                        (peridot/request routing/virkailija-auth-callback
                                        :request-method :get))]
      (testing "callback endpoint should return  identity returned from cas client to session"
        (is (= (get-in response [:response :status]) 401))))))

(deftest handle-logout-test
  (jdbc/with-db-connection [tx embedded-db/db-spec]
    (let [handler (create-handler tx)
          session (peridot/session handler)
          response (-> session
                        (peridot/request routing/virkailija-auth-callback
                          :request-method :get
                          :params {:ticket "ST-15126"})
                        (peridot/request routing/virkailija-auth-logout
                                        :request-method :get))]
      (testing "logout endpoint redirects to cas logout"
        (is (= (get-in response [:response :status]) 302))))))

