(ns yki.handler.exam-payment-new
  (:require
    [clj-time.core :as t]
    [clojure.data.csv :refer [write-csv]]
    [clojure.java.io :as jio]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.file]
    [ring.util.http-response :refer [bad-request internal-server-error ok]]
    [yki.boundary.registration-db :as registration-db]
    [yki.boundary.exam-payment-new-db :as payment-db]
    [yki.boundary.organization :as organization]
    [yki.handler.payment :refer [cancel-redirect error-redirect success-redirect]]
    [yki.handler.routing :as routing]
    [yki.middleware.payment :refer [with-request-validation]]
    [yki.spec :as ys]
    [yki.registration.email :as registration-email]
    [yki.util.db :refer [rollback-on-exception]]
    [yki.util.exam-payment-helper :refer [create-or-return-payment-for-registration! get-payment-amount-for-registration]]
    [yki.util.audit-log :as audit]
    [yki.util.common :refer [format-datetime-for-csv-export]]
    [yki.util.template-util :as template-util])
  (:import (java.io ByteArrayInputStream FileOutputStream InputStream StringWriter)
           (java.time LocalDate)))

(defn- infer-content-type [headers]
  (let [content-type-string (headers "content-type")]
    (when content-type-string
      (cond
        (str/starts-with? content-type-string "text/csv")
        :csv
        (str/starts-with? content-type-string "application/json")
        :edn))))

(defn report-file-path ^String [content-type]
  (case content-type
    :csv
    (str "/tmp/yki/report/csv/" (t/now) ".csv")
    :edn
    (str "/tmp/yki/report/edn/" (t/now) ".edn")))

(defn- store-report! [content-type report-contents]
  (let [path-to-report (report-file-path content-type)]
    (jio/make-parents path-to-report)
    (with-open [fos (FileOutputStream. path-to-report)]
      (if (instance? InputStream report-contents)
        (.transferTo ^InputStream report-contents fos)
        ; Else report-contents is likely a map or string
        ; -> try to just spit it.
        (spit fos report-contents)))))

(defn- registration-redirect [db payment-helper url-helper lang registration]
  (case (:state registration)
    "COMPLETED"
    (url-helper :payment.success-redirect lang (:exam_session_id registration))
    "SUBMITTED"
    (jdbc/with-db-transaction [tx (:spec db)]
      (rollback-on-exception
        tx
        #(let [amount            (:paytrail (get-payment-amount-for-registration payment-helper registration))
               paytrail-response (create-or-return-payment-for-registration! payment-helper tx registration lang amount)
               redirect-url      (paytrail-response "href")]
           redirect-url)))
    ; Other values are unexpected. Redirect to error page.
    (url-helper :payment.error-redirect lang (:exam_session_id registration))))

(def report-csv-fields
  ["Järjestäjä"
   "Maksun aikaleima"
   "Koepäivä"
   "Kieli"
   "Taso"
   "Osallistujan nimi"
   "Osallistujan sähköposti"
   "Summa (€)"
   "Maksun yksilöintitunnus"])

(defn- payment-data->csv-row [url-helper {:keys [amount exam_date form language_code level_code organizer_name paid_at reference]}]
  (let [last-name  (:last_name form)
        first-name (:first_name form)
        email      (:email form)]
    [organizer_name
     (format-datetime-for-csv-export paid_at)
     exam_date
     (template-util/get-language url-helper language_code "fi")
     (template-util/get-level url-helper level_code "fi")
     (str/join ", " [last-name first-name])
     email
     (->>
       (/ amount 100)
       (double)
       (format "%.2f"))
     reference]))

(defn- payments-data->csv-input-stream [url-helper payments-data]
  (let [writer       (StringWriter.)
        payment-rows (->> payments-data
                          (map #(payment-data->csv-row url-helper %))
                          (sort-by (juxt first second)))]
    (write-csv
      writer
      (into [report-csv-fields] payment-rows)
      :separator \;
      :quote? (constantly true))
    (-> (.toString writer)
        (.getBytes)
        (ByteArrayInputStream.))))

(defn- csv-response [{:keys [from to]} body]
  (let [filename    (str "YKI_tutkintomaksut_" from "_" to ".csv")
        csv-headers {"Content-Type"        "text/csv"
                     "Content-Disposition" (str "attachment; filename=\"" filename "\"")}]
    (-> (ok body)
        (update :headers merge csv-headers))))

(defn- with-organizer-names [url-helper payments]
  (when (seq payments)
    (let [organizer-oids      (->> payments
                                   (map :oid)
                                   (distinct))
          oid->organizer-name (->> (organization/get-organizations-by-oids url-helper organizer-oids)
                                   (map (fn [org-data]
                                          [(get org-data "oid")
                                           (get-in org-data ["nimi" "fi"])]))
                                   (into {}))
          with-organizer-name (fn [{:keys [oid] :as val}]
                                (assoc val :organizer_name (oid->organizer-name oid)))]
      (map with-organizer-name payments))))

(defmethod ig/init-key :yki.handler/exam-payment-new [_ {:keys [auth access-log db payment-helper url-helper email-q]}]
  {:pre [(some? auth) (some? access-log) (some? db) (some? email-q) (some? payment-helper) (some? url-helper)]}
  (api
    (context routing/payment-v2-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (GET "/:id/redirect" {session :session}
        :path-params [id :- ::ys/registration_id]
        :query-params [lang :- ::ys/language-code]
        (let [external-user-id     (get-in session [:identity :external-user-id])
              ;external-user-id     "local_test@testi.fi"
              registration-details (registration-db/get-registration-data-for-new-payment db id external-user-id)]
          (if registration-details
            ; Registration details found, redirect based on registration state.
            (ok {:redirect (registration-redirect db payment-helper url-helper lang registration-details)})
            ; No registration matching given id and external-user-id from session.
            (bad-request {:reason :registration-not-found}))))
      (GET "/report" _
        :query-params [from :- ::ys/date-type
                       to :- ::ys/date-type]
        (try
          (let [from-inclusive     (LocalDate/parse from)
                to-exclusive       (-> (LocalDate/parse to)
                                       (.plusDays 1))
                completed-payments (payment-db/get-completed-payments-for-timerange db from-inclusive to-exclusive)]
            (->> completed-payments
                 (with-organizer-names url-helper)
                 (payments-data->csv-input-stream url-helper)
                 (csv-response {:from from
                                :to   to})))
          (catch Exception e
            (log/error e "Failed to generate payment report from" from "to" to)
            (when-let [response (:response (ex-data e))]
              (log/error "Response status code:" (:status response))
              (log/error "Response body:" (:body response)))
            (internal-server-error {})))))
    (context routing/paytrail-payment-root []
      :coercion :spec
      :no-doc true
      :middleware [wrap-params #(with-request-validation (:payment-config payment-helper) %)]
      (GET "/:lang/success" request
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               amount         "checkout-amount"
               payment-status "checkout-status"} (:query-params request)
              payment-details     (registration-db/get-new-payment-details db transaction-id)
              registration-id     (:registration_id payment-details)
              participant-details (registration-db/get-participant-data-by-registration-id db registration-id)
              exam-session-id     (:exam_session_id payment-details)]
          (if (and payment-details
                   (= (int (:amount payment-details))
                      (Integer/parseInt amount))
                   (= "ok" payment-status))
            (let [payment-id                        (:id payment-details)
                  send-registration-complete-email! #(registration-email/send-exam-registration-completed-email!
                                                       email-q
                                                       url-helper
                                                       lang
                                                       participant-details)]
              (if (registration-db/complete-new-payment-and-exam-registration! db registration-id payment-id send-registration-complete-email!)
                (do (audit/log-participant {:request   request
                                            :target-kv {:k audit/payment
                                                        :v (:reference payment-details)}
                                            :change    {:type audit/create-op
                                                        :new  (:params request)}})
                    (log/info "Completed payment with transaction-id" transaction-id ", updating registration with id" registration-id "to COMPLETED."))
                (log/info "Success callback invoked for transaction-id" transaction-id "corresponding to already completed registration with id" registration-id "; this is a no-op."))
              (success-redirect url-helper lang exam-session-id))
            (do
              (log/error "Success callback invoked with unexpected parameters for transaction-id" transaction-id "corresponding to registration with id" registration-id
                         "; amount:" amount "; payment-status:" payment-status)
              (error-redirect url-helper lang exam-session-id)))))
      (GET "/:lang/error" {query-params :query-params}
        :path-params [lang :- ::ys/language-code]
        (let [{transaction-id "checkout-transaction-id"
               payment-status "checkout-status"} query-params
              payment-details (registration-db/get-new-payment-details db transaction-id)]
          (log/info "Error callback invoked for transaction-id" transaction-id
                    "with payment-status" payment-status
                    "while stored payment-status was" (:state payment-details))
          (when (= "UNPAID" (:state payment-details))
            (payment-db/mark-payment-as-cancelled! db (:id payment-details)))
          (if-let [exam-session-id (:exam_session_id payment-details)]
            (cancel-redirect url-helper lang exam-session-id)
            (error-redirect url-helper lang nil)))))
    ; Report generation callback
    (POST "/report" req
      (let [body         (:body req)
            headers      (:headers req)
            content-type (infer-content-type headers)]
        (log/info "REPORT callback invoked with headers:" headers)
        (store-report! content-type body)
        (ok {})))))
