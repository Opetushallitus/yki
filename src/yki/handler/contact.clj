(ns yki.handler.contact
  (:require [compojure.api.sweet :refer [context GET DELETE POST]]
            [yki.boundary.contact-db :as contact-db]
            [yki.util.audit-log :as audit-log]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [ok not-found]]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/contact [_ {:keys [db]}]
  (fn [oid]
    (context "/" []
      :coercion :spec
      (GET "/" []
        :return ::ys/contacts-response
        (let [contacts (contact-db/get-contacts-by-oid db oid)]
          (ok {:contacts contacts})))

      (POST "/" request
        :body [contact ::ys/contact-type]
        :return ::ys/id-response
        (let [created (contact-db/create-contact! db oid contact)]
          (ok {:id created}))))))
