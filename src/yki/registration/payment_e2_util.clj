(ns yki.registration.payment-e2-util
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [yki.util.template-util :as template-util])
  (:import [org.apache.commons.codec.digest DigestUtils]))

(defn- create-authcode [params]
  (let [plaintext (str/join "|" (remove nil? params))]
    (-> plaintext (.getBytes "UTF-8") DigestUtils/sha256Hex str/upper-case)))

(defn- calculate-authcode [{:keys [MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY
                                   AMOUNT ORDER_NUMBER MSG_SETTLEMENT_PAYER
                                   MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]} secret]
  (create-authcode [secret MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY AMOUNT ORDER_NUMBER MSG_SETTLEMENT_PAYER MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]))

(defn generate-form-data [{:keys [paytrail-host yki-payment-uri merchant_id merchant_secret]}
                          amount
                          {:keys [language-code order-number msg]}]
  (let [params-in   "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,URL_NOTIFY,AMOUNT,ORDER_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL,PARAMS_IN,PARAMS_OUT"
        params-out  "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS,PAYMENT_METHOD,SETTLEMENT_REFERENCE_NUMBER"
        form-params {:MERCHANT_ID           merchant_id
                     :LOCALE                (case language-code "fi" "fi_FI" "sv" "sv_SE" "en" "en_US")
                     :URL_SUCCESS           (str yki-payment-uri "/success")
                     :URL_CANCEL            (str yki-payment-uri "/cancel")
                     :URL_NOTIFY            (str yki-payment-uri "/notify")
                     :AMOUNT                amount
                     :ORDER_NUMBER          order-number
                     :MSG_SETTLEMENT_PAYER  msg
                     :MSG_UI_MERCHANT_PANEL msg
                     :PARAMS_IN             params-in
                     :PARAMS_OUT            params-out}
        authcode    (calculate-authcode form-params merchant_secret)]
    {:uri    paytrail-host
     :params (assoc form-params :AUTHCODE authcode)}))

;; Generate subtest item form data t.ex
;; :ITEM_TITLE[0] "Kirjoittaminen"
;; :ITEM_QUANTITY[0] "1"
;; :ITEM_PRICE[0] "50.00"

(defn- param+idx->str [param idx]
  (str param "[" idx "]"))

(defn- subtest-form-rows [subtests amount url-helper lang]
  (into {} (map-indexed (fn [idx val]
                          (assoc {}
                            (param+idx->str "ITEM_TITLE" idx) (template-util/get-subtest url-helper val lang)
                            (param+idx->str "ITEM_UNIT_PRICE" idx) ((keyword val) amount)
                            (param+idx->str "ITEM_QUANTITY" idx) 1)) subtests)))

(defn- subtest-params [subtests]
  (->> (range (count subtests))
       (map (fn [index] (str ",ITEM_TITLE[" index "],ITEM_QUANTITY[" index "],ITEM_UNIT_PRICE[" index "]")))
       (str/join "")))

(comment
  (subtest-params [:abc :def :gh]))


(defn- subtest-values [subtests amount url-helper lang]
  (reduce #(conj % (template-util/get-subtest url-helper %2 lang) "1" ((keyword %2) amount)) [] subtests))

(defn- calculate-evaluation-authcode [{:keys [MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY
                                              ORDER_NUMBER MSG_SETTLEMENT_PAYER
                                              MSG_UI_MERCHANT_PANEL PARAMS_IN PARAMS_OUT]} secret subtests]
  (create-authcode (concat [secret MERCHANT_ID LOCALE URL_SUCCESS URL_CANCEL URL_NOTIFY ORDER_NUMBER MSG_SETTLEMENT_PAYER MSG_UI_MERCHANT_PANEL] subtests [PARAMS_IN PARAMS_OUT])))

(defn generate-evaluation-form-data [{:keys [paytrail-host yki-payment-uri merchant_id merchant_secret amount]}
                                     {:keys [language-code order-number msg]}
                                     url-helper subtests]
  (let [params-in   (str "MERCHANT_ID,LOCALE,URL_SUCCESS,URL_CANCEL,URL_NOTIFY,ORDER_NUMBER,MSG_SETTLEMENT_PAYER,MSG_UI_MERCHANT_PANEL" (subtest-params subtests) ",PARAMS_IN,PARAMS_OUT")
        params-out  "ORDER_NUMBER,PAYMENT_ID,AMOUNT,TIMESTAMP,STATUS,PAYMENT_METHOD,SETTLEMENT_REFERENCE_NUMBER"
        form-params (merge {:MERCHANT_ID           (if (integer? merchant_id) merchant_id (Integer/parseInt merchant_id))
                            :LOCALE                (case language-code "fi" "fi_FI" "sv" "sv_SE" "en" "en_US")
                            :URL_SUCCESS           (str yki-payment-uri "/success")
                            :URL_CANCEL            (str yki-payment-uri "/cancel")
                            :URL_NOTIFY            (str yki-payment-uri "/notify")
                            :ORDER_NUMBER          order-number
                            :MSG_SETTLEMENT_PAYER  msg
                            :MSG_UI_MERCHANT_PANEL msg
                            :PARAMS_IN             params-in
                            :PARAMS_OUT            params-out}
                           (subtest-form-rows subtests amount url-helper language-code))
        authcode    (calculate-evaluation-authcode form-params merchant_secret (subtest-values subtests amount url-helper language-code))]
    {:uri    paytrail-host
     :params (assoc form-params :AUTHCODE authcode)}))

(def response-keys [:ORDER_NUMBER :PAYMENT_ID :AMOUNT :TIMESTAMP :STATUS :PAYMENT_METHOD :SETTLEMENT_REFERENCE_NUMBER])

(defn valid-return-params? [merchant_secret query-params]
  (if-let [return-authcode (:RETURN_AUTHCODE query-params)]
    (let [auth-code-parts     (-> (keep query-params response-keys)
                                  (conj merchant_secret))
          calculated-authcode (->> auth-code-parts
                                   (str/join "|")
                                   DigestUtils/sha256Hex
                                   str/upper-case)]
      (= return-authcode calculated-authcode))
    (log/error "Tried to authenticate message, but the map contained no :RETURN_AUTHCODE key. Data:" query-params)))
