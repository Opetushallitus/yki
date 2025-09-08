(ns yki.handler.person-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [compojure.core :refer [routes]]
    [integrant.core :as ig]
    [peridot.core :as peridot]
    [stub-http.core :refer [with-routes!]]
    [yki.embedded-db :as embedded-db]
    [yki.handler.base-test :as base]
    [yki.handler.routing :as routing]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(deftest person-authentication-test
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          auth           (base/auth url-helper)
          payment-helper (base/create-examination-payment-helper db url-helper)
          handler        (base/person-handler auth url-helper payment-helper)
          routes         (routes handler)
          session        (peridot/session routes)]
      (testing "accessing endpoints under person requires authentication"
        (let [response (-> session
                           (peridot/request routing/person-api-root :request-method :get))]
          (is (= 401 (get-in response [:response :status]))))))))

(defn read-response-json [response]
  (-> response
      (get-in [:response :body])
      (slurp)
      (json/read-str :key-fn keyword)))

(def person-fields [:oid :first_name :last_name :email :phone_number :street_address :post_office :zip])

(deftest person-details-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "COMPLETED")
  (base/insert-unpaid-expired-registration)
  ; Make all registrations belong to person with oid 5.4.3.2.1
  (base/execute! "UPDATE registration SET person_oid='5.4.3.2.1'")
  ; Make exam session 1 recent enough so that registrations are returned through person APIs
  (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 month' WHERE id=1") ;
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)]
      (testing "person details endpoint returns 404 if there is no person linked to user identity"
        (let [fake-auth (ig/init-key :yki.middleware.no-auth/with-fake-session
                                     {:identity    {:oid "OID-not-found"}
                                      :auth-method "SUOMIFI"})
              handler   (base/person-handler fake-auth url-helper payment-helper)
              routes    (routes handler)
              session   (peridot/session routes)
              response  (-> session
                            (peridot/request routing/person-api-root :request-method :get))]
          (is (= 404 (get-in response [:response :status])))))
      (testing "person details are returned if person corresponding to user identity is found from DB"
        (let [person (-> base/registration-form
                         (assoc :oid "5.4.3.2.1")
                         (select-keys person-fields))]
          (testing "all registrations for person are returned when user is strongly authenticated"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid (:oid person)}
                                              :auth-method "SUOMIFI"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= [1 2 3 4] (->> response-data
                                    :registrations
                                    (map :id))))))
          (testing "registrations for exam sessions over a year ago are not returned"
            (let [fake-auth (ig/init-key :yki.middleware.no-auth/with-fake-session
                                         {:identity    {:oid (:oid person)}
                                          :auth-method "SUOMIFI"})
                  handler   (base/person-handler fake-auth url-helper payment-helper)
                  routes    (routes handler)
                  session   (peridot/session routes)]
              ; Move exam session date to just over a year ago -> no registrations are to be returned
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 year 1 day' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= [] (->> response-data
                               :registrations
                               (map :id)))))
              ; Move exam session date to exactly a year ago -> registrations should again be returned
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 year' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= [1 2 3 4] (->> response-data
                                      :registrations
                                      (map :id)))))
              ; Access to registrations to future exam sessions should not be restricted
              (base/execute! "UPDATE exam_date SET exam_date = current_date + interval '10 years' WHERE id=1")
              (let [response      (-> session
                                      (peridot/request routing/person-api-root :request-method :get))
                    response-data (read-response-json response)]
                (is (= 200 (get-in response [:response :status])))
                (is (= person (dissoc response-data :registrations)))
                (is (= [1 2 3 4] (->> response-data
                                      :registrations
                                      (map :id)))))
              ; Finally, reset exam date to a month ago
              (base/execute! "UPDATE exam_date SET exam_date = current_date - interval '1 month' WHERE id=1")))
          (testing "weakly authenticated user only receives details related to registration linked with login code"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid             (:oid person)
                                                            :registration-id 1}
                                              :auth-method "EMAIL"
                                              :auth-target "PERSON"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= [1] (->> response-data
                              :registrations
                              (map :id))))))
          (testing "no registrations are returned for weakly authenticated user in case registration linked with login code is not found"
            (let [fake-auth     (ig/init-key :yki.middleware.no-auth/with-fake-session
                                             {:identity    {:oid             (:oid person)
                                                            :registration-id 999}
                                              :auth-method "EMAIL"
                                              :auth-target "PERSON"})
                  handler       (base/person-handler fake-auth url-helper payment-helper)
                  routes        (routes handler)
                  session       (peridot/session routes)
                  response      (-> session
                                    (peridot/request routing/person-api-root :request-method :get))
                  response-data (read-response-json response)]
              (is (= 200 (get-in response [:response :status])))
              (is (= person (dissoc response-data :registrations)))
              (is (= [] (->> response-data
                             :registrations
                             (map :id)))))))))))

(deftest person-registrations-test
  (base/insert-base-data)
  (base/insert-persons)
  (base/insert-registrations "SUBMITTED")
  (base/insert-unpaid-expired-registration)
  ; Make all registrations belong to person with oid 5.4.3.2.1
  (base/execute! "UPDATE registration SET person_oid='5.4.3.2.1'")
  (with-routes!
    {}
    (let [db             (base/db)
          url-helper     (base/create-url-helper (str "localhost:" port))
          payment-helper (base/create-examination-payment-helper db url-helper)
          oid            "5.4.3.2.1"]
      (testing "strongly authenticated person can view and act on all their registrations"
        (let [fake-auth          (ig/init-key :yki.middleware.no-auth/with-fake-session
                                              {:identity    {:oid oid}
                                               :auth-method "SUOMIFI"})
              handler            (base/person-handler fake-auth url-helper payment-helper)
              routes             (routes handler)
              session            (peridot/session routes)
              confirm-response-1 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 1 "/confirm") :request-method :get))
              response-data-1    (read-response-json confirm-response-1)
              confirm-response-2 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 2 "/confirm") :request-method :get))
              response-data-2    (read-response-json confirm-response-2)]
          (is (= 200 (get-in confirm-response-1 [:response :status])))
          (is (= 1 (:id response-data-1)))
          (is (= 200 (get-in confirm-response-2 [:response :status])))
          (is (= 2 (:id response-data-2)))))
      (testing "weakly authenticated user can only access data related to registration linked with login code"
        (let [fake-auth          (ig/init-key :yki.middleware.no-auth/with-fake-session
                                              {:identity    {:oid             oid
                                                             :registration-id 1}
                                               :auth-method "EMAIL"
                                               :auth-target "PERSON"})
              handler            (base/person-handler fake-auth url-helper payment-helper)
              routes             (routes handler)
              session            (peridot/session routes)
              confirm-response-1 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 1 "/confirm") :request-method :get))
              response-data-1    (read-response-json confirm-response-1)
              confirm-response-2 (-> session
                                     (peridot/request (str routing/person-api-root routing/registration-uri "/" 2 "/confirm") :request-method :get))]
          (is (= 200 (get-in confirm-response-1 [:response :status])))
          (is (= 1 (:id response-data-1)))
          (is (= 401 (get-in confirm-response-2 [:response :status])))
          (is (= nil (get-in confirm-response-2 [:response :body]))))))))
