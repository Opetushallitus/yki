(ns yki.middleware.auth-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clj-time.core :as t]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.middleware.session.store :refer [write-session]]
    [ring.mock.request :as mock]
    [ring.util.codec :refer [form-encode]]
    [ring.util.http-response :refer [ok]]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing])
  (:import
    [org.joda.time DateTime Period]))

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

(deftest session-expiration-test
  (let [session-max-age    3600
        session-config     {:key          "ad7tbRZIG839gDo2"
                            :cookie-attrs {:max-age   session-max-age
                                           :http-only true
                                           :secure    false
                                           :domain    "localhost"
                                           :path      "/yki"}}
        cookie-store       (cookie-store {:key (.getBytes ^String (:key session-config))})
        url-helper         (base/create-url-helper "localhost")
        db                 (base/db)
        auth               (auth-middleware db url-helper session-config)
        handler            (api
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
                                     (assoc :session session)))))
        half-hour-from-now (quot (.getMillis ^DateTime (t/from-now (Period/minutes 30))) 1000)
        half-hour-ago      (quot (.getMillis ^DateTime (t/ago (Period/minutes 30))) 1000)
        base-session       {:auth-method "SUOMIFI"
                            :identity    {:last_name  "Testaaja"
                                          :first_name "Tero"}}]
    (testing "Endpoint that doesn't require authentication can be accessed with or without valid session details"
      (let [url (str routing/user-api-root "/identity")]
        (is (= 200 (:status (request-with-session handler :get url nil))))
        (is (= 200 (:status (request-with-session handler :get url (session->cookie cookie-store {})))))
        (is (= 200 (:status (request-with-session handler :get url (session->cookie cookie-store (assoc base-session :timeout half-hour-from-now))))))
        (is (= 200 (:status (request-with-session handler :get url (session->cookie cookie-store (assoc base-session :timeout half-hour-ago))))))))
    (testing "Endpoint that requires authentication can only be access with valid session cookie"
      (let [url (str routing/registration-api-root "/init")]
        (is (= 200 (:status (request-with-session handler :post url (session->cookie cookie-store (assoc base-session :timeout half-hour-from-now))))))
        (is (= 401 (:status (request-with-session handler :post url (session->cookie cookie-store (assoc base-session :timeout half-hour-ago))))))
        (is (= 401 (:status (request-with-session handler :post url (session->cookie cookie-store base-session)))))
        (is (= 401 (:status (request-with-session handler :post url nil))))))))
