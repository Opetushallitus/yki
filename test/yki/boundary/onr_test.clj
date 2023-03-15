(ns yki.boundary.onr-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [jsonista.core :as json]
    [yki.boundary.cas :refer [CasAccess]]
    [yki.boundary.onr :refer [->OnrClient get-or-create-person normalize-identifier]]
    [yki.handler.base-test :as base]))

(defn- found [person]
  {:status 200
   :body   (json/write-value-as-string person)})

(defn- created [person]
  {:status 201
   :body   (json/write-value-as-string person)})

(defn has-email-identification? [onr-request-body]
  (let [email (->> onr-request-body
                   :yhteystieto
                   first
                   :yhteystietoArvo)]
    (= [{:idpEntityId "oppijaToken"
         :identifier  email}]
       (:identifications onr-request-body))))

(defn has-name-and-birthdate-identification? [onr-request-body]
  (let [{lastname  :sukunimi
         nickname  :kutsumanimi
         birthdate :syntymaaika} onr-request-body]
    (= [{:idpEntityId "oppijaToken"
         :identifier  (->> [lastname nickname birthdate]
                           (str/join "_")
                           (normalize-identifier))}]
       (:identifications onr-request-body))))

(defn classify-onr-search-action [body]
  (cond
    (and (some? (:hetu body))
         (nil? (:identifications body)))
    :ssn

    (and (nil? (:hetu body))
         (has-email-identification? body))
    :email

    (and (nil? (:hetu body))
         (has-name-and-birthdate-identification? body))
    :name+birthdate

    (and (nil? (:hetu body))
         (nil? (:identifications body)))
    :no-identifications

    :else
    :error))

(defrecord MockCasClient [url-helper respond onr-search-statistics]
  CasAccess
  (cas-authenticated-post [_ url body]
    (if (= url (url-helper :onr-service.get-or-create-person))
      (do (swap! onr-search-statistics conj (classify-onr-search-action body))
          (respond body @onr-search-statistics))
      (throw (ex-info "Functionality for URL not implemented" {})))))

(deftest get-or-create-person-test
  (let [url-helper (base/create-url-helper "localhost")]
    (testing "If a new person is created from person details, its OID is returned"
      (let [onr-search-statistics (atom [])
            respond               (constantly (created {:oidHenkilo "5.4.3.2.1"}))
            cas-client            (->MockCasClient url-helper respond onr-search-statistics)
            onr-client            (->OnrClient url-helper cas-client)]
        (is (= "5.4.3.2.1" (get-or-create-person onr-client base/registration-form)))
        (is (= "5.4.3.2.1" (get-or-create-person onr-client (dissoc base/registration-form :ssn))))
        (is (= "5.4.3.2.1" (get-or-create-person onr-client base/registration-form)))
        (is (= [:ssn :email :ssn] @onr-search-statistics))))
    (testing "Existing ONR person is always returned if matched with SSN"
      (let [onr-search-statistics (atom [])
            respond               (constantly (found {:oidHenkilo "5.4.3.2.1"}))
            cas-client            (->MockCasClient url-helper respond onr-search-statistics)
            onr-client            (->OnrClient url-helper cas-client)]
        (is (= "5.4.3.2.1" (get-or-create-person onr-client base/registration-form)))
        (is (= "5.4.3.2.1" (get-or-create-person onr-client {:ssn        (:ssn base/registration-form)
                                                             :first_name "Joku"
                                                             :last_name  "Nimi"})))
        (is (= [:ssn :ssn] @onr-search-statistics))))
    (testing "If SSN is not present, person is searched first with email and then with name+birthdate"
      (let [onr-search-statistics (atom [])
            respond (fn [body stats]
                      (case (count stats)
                        1
                        (found {:oidHenkilo "1.1.1.1.1"
                                :syntymaaika "other-than-requested"})
                        2
                        (found (merge {:oidHenkilo "2.2.2.2.2"}
                                      (select-keys body [:syntymaaika :sukunimi :kutsumanimi])))))
            cas-client (->MockCasClient url-helper respond onr-search-statistics)
            onr-client (->OnrClient url-helper cas-client)]
        (is (= "2.2.2.2.2" (get-or-create-person onr-client (dissoc base/registration-form :ssn))))
        (is (= [:email :name+birthdate] @onr-search-statistics))))
    (testing "If searching by email and name+birthdate yield incorrect matches, creation of a new person is forced by searching with empty identifications"
      (let [onr-search-statistics (atom [])
            respond (fn [body stats]
                      (case (count stats)
                        1
                        (found {:oidHenkilo "1.1.1.1.1"
                                :syntymaaika "other-than-requested"})
                        2
                        (found (merge {:oidHenkilo "2.2.2.2.2" :syntymaaika "this-wont-match"}
                                      (select-keys body [:sukunimi :kutsumanimi])))
                        3
                        (created (assoc body :oidHenkilo "3.3.3.3.3"))))
            cas-client (->MockCasClient url-helper respond onr-search-statistics)
            onr-client (->OnrClient url-helper cas-client)]
        (is (= "3.3.3.3.3" (get-or-create-person onr-client (dissoc base/registration-form :ssn))))
        (is (= [:email :name+birthdate :no-identifications] @onr-search-statistics))))))
