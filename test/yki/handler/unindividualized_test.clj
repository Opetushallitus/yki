(ns yki.handler.unindividualized-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [duct.database.sql]
            [yki.util.url-helper]
            [yki.middleware.auth]
            [yki.handler.base-test :as base]
            [clojure.string :as s]
            [jsonista.core :as j]
            [compojure.core :as core]
            [muuntaja.middleware :as middleware]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [peridot.core :as peridot]
            [stub-http.core :refer :all]
            [yki.boundary.permissions :as permissions]
            [yki.embedded-db :as embedded-db]
            [yki.handler.auth]
            [yki.handler.routing :as routing]
            [yki.handler.unindividualized]))

(use-fixtures :once embedded-db/with-postgres embedded-db/with-migration)
(use-fixtures :each embedded-db/with-transaction)

(defn- create-handlers [port]
  (let 
    [url-helper (base/create-url-helper (str "localhost:" port))
     auth (base/auth url-helper)
     auth-handler (base/auth-handler auth url-helper)
     unindividualized-handler (middleware/wrap-format (ig/init-key :yki.handler/unindividualized {:db (duct.database.sql/->Boundary @embedded-db/conn)
                                                                                                  :auth auth
                                                                                                  :onr-client (base/onr-client url-helper)}))]
      (core/routes unindividualized-handler auth-handler)))

(defn- get-mock-routes [port user]
  (merge
    {"/kayttooikeus-service/kayttooikeus/kayttaja" {:status 200 :content-type "application/json"
                                                    :body (slurp (str "test/resources/" user ".json"))}
    "/oppijanumerorekisteri-service/henkilo/1.2.3.4.5/master" {:status 200 :content-type "application/json"
                                                                :body (slurp "test/resources/onr_henkilo_by_hetu.json")}
    "/kayttooikeus-service/j_spring_cas_security_check" {:status 200
                                                          :headers {"Set-Cookie" "JSESSIONID=eyJhbGciOiJIUzUxMiJ9"}}
    "/cas/serviceValidate" {:status 200 :content-type "application/xml;charset=UTF-8"
                            :body (slurp "test/resources/serviceResponse.xml")}}
    (base/cas-mock-routes port)))

(deftest get-all-unindividualized
  (base/insert-base-data)
  (base/insert-registrations "COMPLETED")
  (jdbc/execute! @embedded-db/conn "UPDATE registration SET created = current_timestamp WHERE person_oid = '5.4.3.2.1'")
  (with-routes!
    (fn [server]
      (merge (get-mock-routes (:port server) "user_with_admin_role")
             {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                                        :body (slurp "test/resources/localisation.json")}}
             {"/oppijanumerorekisteri-service/henkilo/masterHenkilosByOidList" {:status 200 :content-type "application/json"
                                                                                  :body   (j/write-value-as-string {(keyword "5.4.3.2.1") {:oidHenkilo "1.2.4.5.6" :yksiloity false}, 
                                                                                                                    (keyword "1.3.4.5.6") {:oidHenkilo "1.3.4.5.6" :yksiloity true},
                                                                                                                    (keyword "1.2.4.5.8") {:oidHenkilo "1.2.4.5.8" :yksiloity false},
                                                                                                                    (keyword "1.2.4.5.7") {:oidHenkilo "1.2.4.5.7" :yksiloity false}})}}))
      (let [handlers (create-handlers (:port server))
            session  (peridot/session handlers)]
        (testing "should have three unindividualized" 
          (let [response (-> session 
                             (peridot/request routing/virkailija-auth-callback
                                :request-method :get
                                :params {:ticket "ST-15126"})
                             (peridot/request routing/unindividualized-uri :request-method :get))]
            (is (= (get (-> response :response :headers) "Content-Type") "application/json; charset=utf-8"))
            (is (= 3 (count (first (vals (base/body-as-json (:response response)))))))
            (is (= (-> response :response :status) 200))))
        (jdbc/execute! @embedded-db/conn "UPDATE registration SET created = '2018-06-26T14:26:48.632Z' WHERE person_oid = '5.4.3.2.1'")
        (testing "should have zero registration because the singular registration from over year ago"
          (let [request (mock/request :get routing/unindividualized-uri)
                response (base/send-request-with-tx request)]
            (is (= (get (:headers response) "Content-Type") "application/json; charset=utf-8"))
            (is (= 0 (count (first (vals (base/body-as-json response))))))
            (is (= (:status response) 200)))))))