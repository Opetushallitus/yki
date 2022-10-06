(ns yki.handler.registration-commons
  (:require [clojure.java.jdbc :as jdbc]
            [compojure.core :as core]
            [duct.database.sql :as sql]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [stub-http.core :refer [with-routes!]]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]
            [yki.handler.routing :as routing]
            [jsonista.core :as j]
            [peridot.core :as peridot]
            [pgqueue.core :as pgq]))

(defn create-handlers
  [email-q port use-new-payments-api?]
  (let [db                   (sql/->Boundary @embedded-db/conn)
        url-helper           (base/create-url-helper (str "localhost:" port))
        payment-helper       (base/create-examination-payment-helper db url-helper use-new-payments-api?)
        auth                 (base/auth url-helper)
        access-log           (ig/init-key :yki.middleware.access-log/with-logging {:env "unit-test"})
        auth-handler         (base/auth-handler auth url-helper)
        registration-handler (middleware/wrap-format (ig/init-key :yki.handler/registration {:db             db
                                                                                             :url-helper     url-helper
                                                                                             :payment-helper payment-helper
                                                                                             :email-q        email-q
                                                                                             :access-log     access-log
                                                                                             :payment-config base/payment-config
                                                                                             :onr-client     (base/onr-client url-helper)
                                                                                             :auth           auth}))]
    (core/routes registration-handler auth-handler)))

(defn fill-exam-session [registrations kind]
  (dotimes [_ registrations]
    (jdbc/execute! @embedded-db/conn
                   (str "INSERT INTO registration(state, exam_session_id, participant_id, kind) values ('SUBMITTED', 2, 2, '" kind "')"))))

(def registration-form-data
  {:first_name       "Fuu"
   :last_name        "Bar"
   :gender           "1"
   :nationalities    []
   :birthdate        "1999-01-01"
   :certificate_lang "fi"
   :exam_lang        "fi"
   :post_office      "Helsinki;"
   :zip              "01000"
   :street_address   "Ateläniitynpolku 29 G"
   :phone_number     "04012345"
   :email            "test@test.com"})

(defn insert-common-base-data [organizer-oid]
  (base/insert-base-data)
  (base/insert-organizer organizer-oid)
  (base/insert-payment-config organizer-oid)
  (base/insert-languages organizer-oid))

(defn common-route-specs [server]
  (merge (base/cas-mock-routes (:port server))
         {"/lokalisointi/cxf/rest/v1/localisation" {:status 200 :content-type "application/json"
                                                    :body   (slurp "test/resources/localisation.json")}}
         {"/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto" {:status 200 :content-type "application/json"
                                                                              :body   (j/write-value-as-string {:oidHenkilo "1.2.4.5.6"})}}))

(defn common-bindings [server use-new-payments-api?]
  (let [email-q                (base/email-q)
        handlers               (create-handlers email-q (:port server) use-new-payments-api?)
        session                (base/login-with-login-link (peridot/session handlers))
        init-response          (-> session
                                   (peridot/request (str routing/registration-api-root "/init")
                                                    :body (j/write-value-as-string {:exam_session_id 1})
                                                    :content-type "application/json"
                                                    :request-method :post))
        init-response-body     (base/body-as-json (:response init-response))
        registration-id        (init-response-body "registration_id")
        create-twice-response  (-> session
                                   (peridot/request (str routing/registration-api-root "/init")
                                                    :body (j/write-value-as-string {:exam_session_id 1})
                                                    :content-type "application/json"
                                                    :request-method :post))
        registration           (base/select-one (str "SELECT * FROM registration WHERE id = " registration-id))
        submit-response        (-> session
                                   (peridot/request (str routing/registration-api-root "/" registration-id "/submit" "?lang=fi")
                                                    :body (j/write-value-as-string registration-form-data)
                                                    :content-type "application/json"
                                                    :request-method :post))
        payment                (if use-new-payments-api?
                                 (base/select-one (str "SELECT * FROM exam_payment_new WHERE registration_id = " registration-id))
                                 (base/select-one (str "SELECT * FROM payment WHERE registration_id = " registration-id)))
        payment-link           (base/select-one (str "SELECT * FROM login_link WHERE registration_id = " registration-id))
        submitted-registration (base/select-one (str "SELECT * FROM registration WHERE id = " registration-id))
        email-request          (pgq/take email-q)]
    {:session                session
     :init-response          init-response
     :init-response-body     init-response-body
     :registration           registration
     :registration-id        registration-id
     :create-twice-response  create-twice-response
     :submit-response        submit-response
     :payment                payment
     :payment-link           payment-link
     :submitted-registration submitted-registration
     :email-request          email-request}))

(defn registration-success-redirect [registration-id port use-new-payments-api?]
  (str "http://yki.localhost:"
       port
       "/yki/maksu"
       (when use-new-payments-api? "/v2")
       "/ilmoittautuminen/"
       registration-id
       "?lang=fi"))