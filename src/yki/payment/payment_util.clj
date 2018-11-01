(ns yki.payment.payment-util
  (:require [clojure.spec.alpha :as s]
            [yki.spec :as ys]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time.format DateTimeFormatter]
           [java.util Locale]
           [org.apache.commons.codec.digest DigestUtils]))

(defn- calculate-authcode [{::ys/keys [MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL
                                       AMOUNT ORDER_NUMBER REFERENCE_NUMBER MSG_SETTLEMENT_PAYER
                                       MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]} secret]
  (let [plaintext (str/join "|" (->> [secret MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL
                                      AMOUNT ORDER_NUMBER REFERENCE_NUMBER  MSG_SETTLEMENT_PAYER
                                      MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]
                                     (remove nil?)))]
    (-> plaintext (.getBytes "ISO-8859-1") DigestUtils/sha256Hex str/upper-case)))

(defn generate-form-data [{:keys [paytrail-host yki-paytrail-uri merchant-id merchant-secret amount msg]}
                          {:keys [language-code order-number reference-number] :as params}]
  (let [params-in     "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,AMOUNT,ORDER_NUMBER,REFERENCE_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL,PARAMS_IN,PARAMS_OUT"
        params-out    "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS"
        localized-msg ((keyword language-code) msg)
        form-params {:MERCHANT_ID  merchant-id
                     :LOCALE       (case language-code "fi" "fi_FI" "sv" "sv_SE")
                     :URL_SUCCESS  (str yki-paytrail-uri "/success")
                     :URL_CANCEL   (str yki-paytrail-uri "/cancel")
                     :AMOUNT       amount
                     :ORDER_NUMBER order-number
                     :REFERENCE_NUMBER reference-number
                     :MSG_SETTLEMENT_PAYER localized-msg
                     :MSG_UI_MERCHANT_PANEL localized-msg
                     :PARAMS_IN params-in
                     :PARAMS_OUT params-out}
        authcode (calculate-authcode form-params merchant-secret)]
    {:uri  paytrail-host
     :pt-payment-form-params (assoc form-params :AUTHCODE authcode)}))

(def response-keys [:ORDER_NUMBER :PAYMENT_ID :AMOUNT :TIMESTAMP :STATUS])

(defn return-authcode-valid? [{:keys [merchant-secret]} form-data]
  (if-let [return-authcode (:RETURN_AUTHCODE form-data)]
    (let [plaintext (-> (->> response-keys
                             (map #(% form-data))
                             (remove nil?)
                             (str/join "|"))
                        (str "|" merchant-secret))
          calculated-authcode (-> plaintext DigestUtils/sha256Hex str/upper-case)]
      (= return-authcode calculated-authcode))
    (log/error "Tried to authenticate message, but the map contained no :RETURN_AUTHCODE key. Data:" form-data)))
