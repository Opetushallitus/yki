(ns yki.boundary.email
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [yki.boundary.cas :as cas]
    [yki.config :refer [oph-oid]]
    [yki.util.http-util :as http-util])
  (:import (org.asynchttpclient.request.body.multipart ByteArrayPart)))

(defn- log-disabled-email [recipients subject body attachments]
  (log/info
    (str/join "\r\n"
              ["Email sending is disabled, logging instead:"
               (str "Recipients: " recipients)
               (str "Subject: " subject)
               (str "Body: " body)
               (str "Attachments: ["
                    (str/join "," (map :name attachments))
                    "]")])))

(defprotocol Email
  (send-email! [_ email disabled?]))

(defrecord OldEmailService [url-helper]
  Email
  (send-email! [_ email disabled?]
    (let [{:keys [recipients subject body attachments]} email]
      (if disabled?
        (log-disabled-email recipients subject body attachments)
        (let [email-data         {:subject     subject
                                  :html        true
                                  :body        body
                                  :attachments attachments
                                  :charset     "UTF-8"}
              wrapped-recipients (mapv (fn [rcp] {:email rcp}) recipients)
              url                (url-helper :ryhmasahkoposti-service)
              response           (http-util/do-post url {:headers      {"content-type" "application/json; charset=UTF-8"}
                                                         :query-params {:sanitize "false"}
                                                         :body         (json/write-value-as-string {:email     email-data
                                                                                                    :recipient wrapped-recipients})})]
          (when (not= 200 (:status response))
            (throw (Exception. (str "Could not send email to " (str/join recipients))))))))))

(defn- attachment->body-part [{:keys [name data contentType]}]
  (ByteArrayPart. "liite" data contentType nil name))

(defn- upload-attachment! [url-helper cas-client attachment]
  (let [upload-attachment-url (url-helper :new-email-service.attachments)
        body-parts            [(attachment->body-part attachment)]
        {:keys [status body]} (cas/cas-authenticated-post-multipart-form-data cas-client upload-attachment-url body-parts)
        response-body         (json/read-value body)]
    (if (= 200 status)
      (response-body "liiteTunniste")
      (throw (ex-info "Error uploading attachment!" {:status     status
                                                     :body       response-body
                                                     :attachment (:name attachment)})))))

(defn- email->message [{:keys [recipients subject body language metadata]} attachment-ids]
  {:otsikko                 subject
   :sisalto                 body
   :sisallonTyyppi          "html"
   :kielet                  (some-> language (vector))
   ; TODO Verify name and email for sender
   ; TODO Possibly different reply-to address?
   ; TODO Internationalization of name?
   :lahettaja               {:nimi             "Yleiset kielitutkinnot / Opetushallitus"
                             :sahkopostiOsoite "noreply@opintopolku.fi"}
   ; TODO Modify queued data to allow setting recipient name as well as email address!
   :vastaanottajat          (->> recipients
                                 (mapv (fn [email]
                                         {:sahkopostiOsoite email})))
   ; Enforce normal priority. If we were to use high priority, we'd need to throttle the rate of high priority messages ourselves.
   :prioriteetti            "normaali"
   ; TODO Allow customizing retention period?
   :sailytysaika            365
   :lahettavaPalvelu        "yki"
   :metadata                metadata
   ; TODO Ensure same queued email always gets the same idempotencyKey
   :idempotencyKey          (random-uuid)
   :kayttooikeusRajoitukset [{:oikeus       "APP_YKI_YLLAPITAJA"
                              :organisaatio oph-oid}]
   :liitteidenTunnisteet    attachment-ids
   }
  )

(defrecord NewEmailService [url-helper cas-client]
  Email
  (send-email! [_ email disabled?]
    (let [{:keys [recipients subject body attachments metadata message-id]} email]
      (if disabled?
        (log-disabled-email recipients subject body attachments)
        ; TODO Consider failure modes!
        ; TODO Eg. if uploading an attachment fails, retry whole operation?
        ; TODO Not a problem right now, but what if there were multiple attachments and uploading only one of them would persistently fail?
        ; TODO Should other uploaded attachments be explicitly removed? AFAIK, this is not even supported by the email service.
        (let [send-msg-endpoint (url-helper :new-email-service.messages)
              attachment-ids    (mapv #(upload-attachment! url-helper cas-client %) attachments)
              {:keys [status body]} (cas/cas-authenticated-post cas-client send-msg-endpoint (email->message email attachment-ids))
              response-body     (json/read-value body)]
          (if (= 200 status)
            ; TODO Store returned fields viestiTunniste and lahetysTunniste in DB?
            response-body
            (throw (ex-info "Error sending email!" {:status     status
                                                    :body       response-body
                                                    ; TODO message-id and metadata are not yet populated!
                                                    :message-id message-id
                                                    :metadata   metadata}))))))))

(defmethod ig/init-key ::email-client [_ {:keys [use-new-email-service? cas-client url-helper]}]
  {:pre [(boolean? use-new-email-service?)
         (or (not use-new-email-service?)
             (some? cas-client))
         (some? url-helper)]}
  (if use-new-email-service?
    (->NewEmailService url-helper (cas-client (url-helper :new-email-service.cas-login)))
    (->OldEmailService url-helper)))
