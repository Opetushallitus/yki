(ns yki.middleware.auth-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clj-time.core :as t]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.middleware.session.store :refer [write-session]]
    [ring.mock.request :as mock]
    [ring.util.codec :refer [form-encode]]
    [ring.util.http-response :refer [ok]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing])
  (:import
    [org.joda.time DateTime Period]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- auth-middleware [db url-helper session-config]
  (ig/init-key
    :yki.middleware.auth/with-authentication
    {:url-helper     url-helper
     :db             db
     :session-config session-config}))

(defn- request-with-session [handler method url session-cookie]
  (let [request (cond-> (mock/request method url)
                        session-cookie
                        (mock/cookie "yki" session-cookie))]
    (handler request)))

(defn- session->cookie [cookie-store data]
  (-> (write-session cookie-store "yki" data)
      (form-encode)))

(let [session-max-age    3600
      session-config     {:key          "ad7tbRZIG839gDo2"
                          :cookie-attrs {:max-age   session-max-age
                                         :http-only true
                                         :secure    false
                                         :domain    "localhost"
                                         :path      "/yki"}}
      cookie-store       (cookie-store {:key (.getBytes ^String (:key session-config))})
      url-helper         (base/create-url-helper "localhost")
      create-handlers    (fn []
                           (let [db   (base/db)
                                 auth (auth-middleware db url-helper session-config)]
                             (api
                               (context routing/user-api-root []
                                 :middleware [auth]
                                 ; /user/identity is an unauthenticated endpoint (see yki.middleware.auth/rules)
                                 (GET "/identity" {session :session}
                                   (-> (ok {})
                                       (assoc :session session))))
                               (context routing/registration-api-root []
                                 :middleware [auth]
                                 ; /api/registration/init is an authenticated endpoint (see yki.middleware.auth/rules)
                                 (POST "/init" {session :session}
                                   (-> (ok {})
                                       (assoc :session session))))
                               (context routing/virkailija-api-root []
                                 :middleware [auth]
                                 ; /api/virkailija/organizer is an authenticated endpoint
                                 ; for virkailija roles (see yki.middleware.auth/rules
                                 (GET "/organizer" {session :session}
                                   (-> (ok {})
                                       (assoc :session session)))))))
      half-hour-from-now (quot (.getMillis ^DateTime (t/from-now (Period/minutes 30))) 1000)
      half-hour-ago      (quot (.getMillis ^DateTime (t/ago (Period/minutes 30))) 1000)
      oppija-ticket      "ST-foo-bar-123"
      oppija-session     {:auth-method "SUOMIFI"
                          :identity    {:last_name  "Testaaja"
                                        :first_name "Tero"
                                        :ticket     oppija-ticket}}]
  (deftest session-expiration-test
    (testing "Endpoint that doesn't require authentication can be accessed with or without valid session details"
      (let [url      (str routing/user-api-root "/identity")
            handlers (create-handlers)]
        (is (= 200 (:status (request-with-session handlers :get url nil))))
        (is (= 200 (:status (request-with-session handlers :get url (session->cookie cookie-store {})))))
        (is (= 200 (:status (request-with-session handlers :get url (session->cookie cookie-store (assoc oppija-session :timeout half-hour-from-now))))))
        (is (= 200 (:status (request-with-session handlers :get url (session->cookie cookie-store (assoc oppija-session :timeout half-hour-ago))))))))
    (testing "Endpoint that requires authentication can only be accessed with valid session cookie"
      (base/execute! (str "INSERT INTO cas_oppija_ticketstore (ticket) VALUES ('" oppija-ticket "');"))
      (let [url      (str routing/registration-api-root "/init")
            handlers (create-handlers)]
        (is (= 200 (:status (request-with-session handlers :post url (session->cookie cookie-store (assoc oppija-session :timeout half-hour-from-now))))))
        (is (= 401 (:status (request-with-session handlers :post url (session->cookie cookie-store (assoc oppija-session :timeout half-hour-ago))))))
        (is (= 401 (:status (request-with-session handlers :post url (session->cookie cookie-store oppija-session)))))
        (is (= 401 (:status (request-with-session handlers :post url nil)))))))

  (deftest oppija-and-virkailija-ticket-handling-test
    (let [valid-oppija-session        (assoc oppija-session :timeout half-hour-from-now)
          virkailija-ticket           "ST-virkailija-456"
          valid-virkailija-session    {:auth-method "CAS"
                                       :identity    {:ticket virkailija-ticket}
                                       :timeout     half-hour-from-now}
          handlers                    (create-handlers)
          init-registration-url       (str routing/registration-api-root "/init")
          virkailija-organization-url (str routing/virkailija-api-root "/organizer")]
      (base/execute! (str "INSERT INTO cas_oppija_ticketstore (ticket) VALUES ('" oppija-ticket "');"))
      (base/execute! (str "INSERT INTO cas_ticketstore (ticket) VALUES ('" virkailija-ticket "');"))
      (testing "Public APIs require valid oppija session"
        (is (= 200
               (->>
                 valid-oppija-session
                 (session->cookie cookie-store)
                 (request-with-session handlers :post init-registration-url)
                 (:status))))
        (is (= 401
               (->>
                 valid-virkailija-session
                 (session->cookie cookie-store)
                 (request-with-session handlers :post init-registration-url)
                 (:status)))))
      (testing "Virkailija APIs require valid virkailija session"
        (is (= 200
               (->>
                 valid-virkailija-session
                 (session->cookie cookie-store)
                 (request-with-session handlers :get virkailija-organization-url)
                 (:status))))
        (is (= 401
               (->>
                 valid-oppija-session
                 (session->cookie cookie-store)
                 (request-with-session handlers :get virkailija-organization-url)
                 (:status))))))))
