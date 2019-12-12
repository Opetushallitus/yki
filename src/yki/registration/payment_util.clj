(ns yki.registration.payment-util
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time.format DateTimeFormatter]
           [java.util Locale]
           [org.apache.commons.codec.digest DigestUtils]))

(defn- calculate-authcode [{:keys [MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY
                                   AMOUNT ORDER_NUMBER MSG_SETTLEMENT_PAYER
                                   MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]} secret]
  (let [plaintext (str/join "|" (remove nil? [secret MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY AMOUNT ORDER_NUMBER MSG_SETTLEMENT_PAYER MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]))]
    (-> plaintext (.getBytes "UTF-8") DigestUtils/sha256Hex str/upper-case)))

(defn generate-form-data [{:keys [paytrail-host yki-payment-uri merchant_id merchant_secret]}
                          amount
                          {:keys [language-code order-number msg] :as params}]
  (let [params-in     "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,URL_NOTIFY,AMOUNT,ORDER_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL,PARAMS_IN,PARAMS_OUT"
        params-out    "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS,PAYMENT_METHOD,SETTLEMENT_REFERENCE_NUMBER"
        form-params {:MERCHANT_ID  merchant_id
                     :LOCALE       (case language-code "fi" "fi_FI" "sv" "sv_SE" "en" "en_US")
                     :URL_SUCCESS  (str yki-payment-uri "/success")
                     :URL_CANCEL   (str yki-payment-uri "/cancel")
                     :URL_NOTIFY   (str yki-payment-uri "/notify")
                     :AMOUNT       amount
                     :ORDER_NUMBER order-number
                     :MSG_SETTLEMENT_PAYER msg
                     :MSG_UI_MERCHANT_PANEL msg
                     :PARAMS_IN params-in
                     :PARAMS_OUT params-out}
        authcode (calculate-authcode form-params merchant_secret)]
    {:uri  paytrail-host
     :params (assoc form-params :AUTHCODE authcode)}))

(def response-keys [:ORDER_NUMBER :PAYMENT_ID :AMOUNT :TIMESTAMP :STATUS :PAYMENT_METHOD :SETTLEMENT_REFERENCE_NUMBER])

(defn valid-return-params? [merchant_secret query-params]
  (if-let [return-authcode (:RETURN_AUTHCODE query-params)]
    (let [plaintext (str (->> response-keys
                             (map #(% query-params))
                             (remove nil?)
                             (str/join "|"))
                        "|" merchant_secret)
          calculated-authcode (-> plaintext DigestUtils/sha256Hex str/upper-case)]
      (= return-authcode calculated-authcode))
    (log/error "Tried to authenticate message, but the map contained no :RETURN_AUTHCODE key. Data:" query-params)))
