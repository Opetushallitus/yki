(ns yki.handler.registration-commons
  (:require [clojure.java.jdbc :as jdbc]
            [compojure.core :as core]
            [duct.database.sql :as sql]
            [integrant.core :as ig]
            [muuntaja.middleware :as middleware]
            [yki.embedded-db :as embedded-db]
            [yki.handler.base-test :as base]))

(defn create-handlers
  [email-q port use-new-payments-api?]
  (let [db                   (sql/->Boundary @embedded-db/conn)
        url-helper           (base/create-url-helper (str "localhost:" port))
        payment-helper       (base/create-payment-helper db url-helper use-new-payments-api?)
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
