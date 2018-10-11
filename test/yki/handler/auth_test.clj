(ns yki.handler.auth-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [yki.handler.routing :as routing]))

(defn- create-routes [port]
  (let [uri (str "localhost:" port)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri :yki-host uri :liiteri-host uri :protocol-base "http"})
        auth (ig/init-key :yki.middleware.auth/with-authentication
                          {:url-helper url-helper
                           :session-config {:key "ad7tbRZIG839gDo2"
                                            :cookie-attrs {:max-age 28800
                                                           :http-only true
                                                           :secure false
                                                           :path "/yki"}}})
        auth-handler (middleware/wrap-format (ig/init-key :yki.handler/auth {:auth auth
                                                                             :url-helper url-helper
                                                                             :permissions-client {}
                                                                             :cas-client {}}))]
    auth-handler))

(deftest redirect-unauthenticated-user-to-authentication-test
  (let [handler (create-routes 8080)
        session (peridot/session handler)
        response (-> session
                     (peridot/request routing/auth-root
                                      :request-method :get))]
    (testing "callback endpoint should return Unauthorized when callback doesn't include ticket"
      (is (= (get-in response [:response :status]) 303)))))
