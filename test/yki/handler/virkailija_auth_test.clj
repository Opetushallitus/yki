(ns yki.handler.virkailija-auth-test
  (:require [clojure.test :refer [deftest use-fixtures testing is]]
            [compojure.core :refer [routes]]
            [duct.database.sql]
            [integrant.core :as ig]
            [jsonista.core :as j]
            [muuntaja.middleware :as middleware]
            [peridot.core :as peridot]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- get-mock-routes [port user]
  (merge
   {"/kayttooikeus-service/kayttooikeus/kayttaja" {:status 200 :content-type "application/json"
                                                   :body (slurp (str "test/resources/" user ".json"))}
    "/oppijanumerorekisteri-service/henkilo/1.2.3.4.5/master" {:status 200 :content-type "application/json"
                                                               :body (slurp "test/resources/onr_henkilo_by_hetu.json")}
    "/kayttooikeus-service/j_spring_cas_security_check" {:status 200
                                                         :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"
                                                                   "Caller-Id" "1.2.246.562.10.00000000001.yki"}}
    "/cas/serviceValidate" {:status 200 :content-type "application/xml;charset=UTF-8"
                            :body (slurp "test/resources/serviceResponse.xml")
                            :headers {"Caller-Id" "1.2.246.562.10.00000000001.yki"}}}
   (base/cas-mock-routes port)))

(defn- create-routes
  [port]
  (let [uri (str "localhost:" port)
        db (duct.database.sql/->Boundary @embedded-db/conn)
        url-helper (ig/init-key :yki.util/url-helper {:virkailija-host uri
                                                      :oppija-host uri
                                                      :yki-register-host uri
                                                      :yki-host-virkailija ""
                                                      :alb-host (str "http://" uri)
                                                      :scheme "http"})
        auth (base/auth url-helper)
        exam-session-handler (ig/init-key :yki.handler/exam-session {:db db
                                                                     :url-helper url-helper
                                                                     :email-q (base/email-q)
                                                                     :data-sync-q (base/data-sync-q)})

        exam-date-handler (ig/init-key :yki.handler/exam-date {:db db})

        org-handler (middleware/wrap-format (ig/init-key :yki.handler/organizer {:db db
                                                                                 :access-log (base/access-log)
                                                                                 :data-sync-q  (base/data-sync-q)
                                                                                 :url-helper url-helper
                                                                                 :exam-session-handler exam-session-handler
                                                                                 :exam-date-handler exam-date-handler
                                                                                 :auth auth}))
        auth-handler (base/auth-handler auth url-helper)]
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
        organizer-get (-> session
                          (peridot/request routing/organizer-api-root
                                           :request-method :get))

        organizer-delete (-> session
                             (peridot/request (str routing/organizer-api-root "/1.2.3.6")
                                              :request-method :delete))
        exam-session-post (-> session
                              (peridot/request (str routing/organizer-api-root "/1.2.3.4" routing/exam-session-uri)
                                               :body base/exam-session
                                               :content-type "application/json"
                                               :request-method :post))
        exam-session-id (if (= (-> exam-session-post :response :status) 200)
                          ((base/body-as-json (:response exam-session-post)) "id")
                          9999)
        exam-session-put (-> session
                             (peridot/request (str routing/organizer-api-root "/1.2.3.4" routing/exam-session-uri "/" exam-session-id)
                                              :body base/exam-session
                                              :content-type "application/json"
                                              :request-method :put))
        exam-session-get (-> session
                             (peridot/request (str routing/organizer-api-root "/1.2.3.4" routing/exam-session-uri)
                                              :request-method :get))
        exam-session-delete (-> session
                                (peridot/request (str routing/organizer-api-root "/1.2.3.4" routing/exam-session-uri "/" exam-session-id)
                                                 :request-method :delete))]
    {:org {:post organizer-post
           :put organizer-put
           :delete organizer-delete
           :get organizer-get}
     :exam {:post exam-session-post
            :put exam-session-put
            :delete exam-session-delete
            :get exam-session-get}}))

(defn- assert-status-code
  [response code]
  (is (= (-> response :response :status) code)))

(deftest handle-authentication-success-callback-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "user_with_organizer_role"))
    (let [routes (create-routes port)
          session (peridot/session routes)
          response (-> session
                       (peridot/request (str routing/auth-root routing/virkailija-auth-uri "?success-redirect=/yki/auth/user"))
                       (peridot/request routing/virkailija-auth-callback
                                        :request-method :get
                                        :params {:ticket "ST-15126"})
                       (peridot/follow-redirect))
          response-body (j/read-value (slurp (:body (:response response)) :encoding "UTF-8"))
          id (response-body  "identity")
          organizations (id "organizations")]
      (testing "callback endpoint should set identity returned from cas client to session"
        (is (= (id "username") "test")))
      (testing "callback endpoint should set lang returned from onr to session"
        (is (= (id "lang") "sv")))
      (testing "callback endpoint should set person oid returned from permissions client to session"
        (is (= (id "oid") "1.2.3.4.5")))
      (testing "callback endpoint should set only YKI permissions returned from permissions client to session"
        (is (= organizations
               [{"oid" "1.2.3.4" "permissions" [{"oikeus" "JARJESTAJA" "palvelu" "YKI"}]}]))))))

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
      (get-mock-routes (:port server) "user_with_organizer_role"))
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

(deftest user-with-organizer-role-authorization-test
  (let [org-1-oid "1.2.3.4"
        org-2-oid "1.2.3.5"]
    (base/insert-organizer org-1-oid)
    (base/insert-organizer org-2-oid)
    (base/insert-languages org-1-oid)
    (base/insert-languages org-2-oid)
    (base/insert-exam-dates))

  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "user_with_organizer_role"))
    (let [responses (fire-requests port)
          org-responses (:org responses)
          exam-responses (:exam responses)
          organizers ((base/body-as-json (-> org-responses :get :response)) "organizers")]
      (testing "post organizer should not be allowed"
        (is (= (-> org-responses :post :response :status) 403)))
      (testing "put organizer should not be allowed"
        (is (= (-> org-responses :put :response :status) 403)))
      (testing "delete organizer should not be allowed"
        (is (= (-> org-responses :delete :response :status) 403)))
      (testing "get organizer should return only organizers that user has permissions to see"
        (is (= (count organizers) 1))
        (is (= (get (first organizers) "oid") "1.2.3.4")))
      (testing "post exam-session should be allowed"
        (is (= (-> exam-responses :post :response :status) 200)))
      (testing "put exam-session should be allowed"
        (is (= (-> exam-responses :put :response :status) 200)))
      (testing "get exam-session should be allowed"
        (is (= (-> exam-responses :get :response :status) 200)))
      (testing "delete exam-session should be allowed"
        (is (= (-> exam-responses :get :response :status) 200))))))

(deftest user-with-admin-permissions-authorization-test
  (base/insert-organizer "1.2.3.5")
  (base/insert-exam-dates)

  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "user_with_admin_role"))
    (let [responses (fire-requests port)
          org-responses (:org responses)
          exam-responses (:exam responses)
          organizers ((base/body-as-json (-> org-responses :get :response)) "organizers")]
      (testing "should allow all operations on organizer"
        (assert-status-code (:get org-responses) 200)
        (is (= (count organizers) 2))
        (assert-status-code (:put org-responses) 200)
        (assert-status-code (:delete org-responses) 404)
        (assert-status-code (:post org-responses) 200)
        (assert-status-code (:get exam-responses) 200)
        (assert-status-code (:put exam-responses) 200)
        (assert-status-code (:delete exam-responses) 200)
        (assert-status-code (:post exam-responses) 200)))))

(deftest user-without-permissions-authorization-test
  (with-routes!
    (fn [server]
      (get-mock-routes (:port server) "no_permissions_user"))
    (let [responses       (fire-requests port)
          exam-responses  (:exam responses)
          org-responses   (:org responses)
          organizers      ((base/body-as-json (-> org-responses :get :response)) "organizers")]
      (testing "should not allow any endpoints"
        (assert-status-code (:get org-responses) 200)
        (is (= (count organizers) 0))
        (assert-status-code (:put org-responses) 403)
        (assert-status-code (:delete org-responses) 403)
        (assert-status-code (:post org-responses) 403)
        (assert-status-code (:get exam-responses) 403)
        (assert-status-code (:put exam-responses) 403)
        (assert-status-code (:delete exam-responses) 403)
        (assert-status-code (:post exam-responses) 403)))))

(deftest unauthenticated-user-test
  (let [routes (create-routes 8080)
        session (peridot/session routes)
        response (-> session
                     (peridot/request routing/organizer-api-root
                                      :body (j/write-value-as-string base/organizer)
                                      :content-type "application/json"
                                      :request-method :post))]

    (testing "should return 401 for unauthenticated user"
      (is (= (get-in response [:response :status]) 401)))))

