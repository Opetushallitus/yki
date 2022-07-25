(ns yki.util.payment-helper
  (:require [clj-time.core :as t]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [jeesql.core :refer [require-sql]]
            [org.httpkit.client :as http]
            [yki.util.payments-api :refer [sign-request]]))

(require-sql ["yki/queries.sql" :as q])

(defn- registration->payment-amount [payment-config registration-details]
  (let [level-code (keyword (:level_code registration-details))]
    (->> [:amount level-code]
         (get-in payment-config)
         (bigdec))))

(defprotocol PaymentHelper
  (get-payment-redirect-url [this registration-id lang])
  (get-payment [this registration-id oid])
  (get-payment-amount-for-registration [this registration-details])
  (get-payment-by-order-number [this order-number])
  (create-payment-for-registration! [this tx registration language amount])
  (initialise-payment-on-registration? [this]))

(defrecord LegacyPaymentHelper [db url-helper payment-config]
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.old.redirect registration-id lang))
  (get-payment [_ registration-id oid]
    (first (q/select-payment-by-registration-id
             (:spec db)
             {:registration_id registration-id :oid oid})))
  (get-payment-amount-for-registration [_ registration-details]
    (registration->payment-amount payment-config registration-details))
  (get-payment-by-order-number [_ order-number]
    (first (q/select-payment-by-order-number (:spec db) {:order_number order-number})))
  (create-payment-for-registration! [_ tx registration language amount]
    (let [order-number-seq (:nextval (first (q/select-next-order-number-suffix tx)))
          oid-last-part    (last (str/split (:oid registration) #"\."))
          order-number     (str "YKI" oid-last-part (format "%09d" order-number-seq))
          payment          {:registration_id (:id registration)
                            :amount          amount
                            :lang            language
                            :order_number    order-number}
          ]
      (q/insert-legacy-payment<! tx payment)))
  (initialise-payment-on-registration? [_]
    true))

(comment
  {
   ; Merchant unique identifier for order.
   ; Cannot be used twice for a create payment payload for same order!
   ;"stamp"        "YKI-1357902468-5"
   "stamp"        (random-uuid)
   ; Order reference
   "reference"    "YKI-1357902468"
   ; Total amount in EUR cents
   "amount"       1337
   ; Currency, only EUR is supported for now
   "currency"     "EUR"
   ; Language: one of FI/SV/EN
   "language"     "FI"
   ; Customer information
   "customer"     {"email" "pyry.koivisto+paytrail-payments-test@mavericks.fi"}
   ; Where to redirect browser after payment is paid or cancelled
   "redirectUrls" {"success" "https://yki.untuvaopintopolku.fi/yki/api/payment/v2/paytrail/success"
                   "cancel"  "https://yki.untuvaopintopolku.fi/yki/api/payment/v2/paytrail/error"}}

  " -- name: select-registration-details-for-new-payment
  SELECT re.exam_session_id,
  re.participant_id,
  re.kind,
  re.form,
  p.email,
  p.external_user_id,
  esl.name,
  es.language_code,
  es.level_code,
  ed.exam_date
  FROM registration re
  INNER JOIN exam_session es ON es.id = re.exam_session_id
  INNER JOIN exam_date ed ON ed.id = es.exam_date_id
  INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
  INNER JOIN participant p ON p.id = re.participant_id
  WHERE re.id = :id
  AND re.participant_id = :participant_id"
  )

(def test-merchant-id "375917")
(def payments-API-URI "https://services.paytrail.com/payments")

(defn authentication-headers [method merchant-id transaction-id]
  (cond-> {"checkout-account"   merchant-id
           "checkout-algorithm" "sha512"
           "checkout-method"    method
           "checkout-nonce"     (str (random-uuid))
           "checkout-timestamp" (str (t/now))}
          (some? transaction-id)
          (assoc "checkout-transaction-id" transaction-id)))

(defn create-payment-data [registration language amount]
  (let [{registration-id            :id
         exam-session-location-name :name
         exam-session-id            :exam_session_id
         email                      :email
         registration-form          :form} registration]
    {"stamp"        (random-uuid)
     ; Order reference
     "reference"    (str/join "_"
                              ["YKI"
                               ; TODO Y-tunnus instead of organizer name
                               exam-session-location-name
                               exam-session-id
                               registration-id])
     ; Total amount in EUR cents
     "amount"       amount
     "currency"     "EUR"
     "language"     (str/upper-case language)
     "customer"     {"email"     email
                     "firstName" (:first_name registration-form)
                     "lastName"  (:last_name registration-form)}
     ; TODO Fix redirectUrls
     "redirectUrls" {"success" "https://yki.untuvaopintopolku.fi/yki/api/payment/v2/paytrail/success"
                     "cancel"  "https://yki.untuvaopintopolku.fi/yki/api/payment/v2/paytrail/error"}
     ; TODO Add also callbackUrls
     ; TODO Add items so that we can have descriptions in receipts?
     }))

(defn create-paytrail-payment! [payment-data]
  (let [authentication-headers (authentication-headers "POST" test-merchant-id nil)
        body                   (json/write-str payment-data)
        signature              (sign-request authentication-headers body)
        headers                (assoc authentication-headers
                                 "content-type" "application/json; charset=utf-8"
                                 "signature" signature)]

    @(http/post
       payments-API-URI
       {:headers headers
        :body    body})))

(defrecord NewPaymentHelper [db url-helper payment-config]
  PaymentHelper
  (get-payment-redirect-url [_ registration-id lang]
    (url-helper :payment-link.new.redirect registration-id lang))
  (get-payment [_ registration-id oid]
    ; TODO
    nil)
  (get-payment-amount-for-registration [_ registration-details]
    ; Unit of returned amount is EUR. Return corresponding amount in minor unit, ie. cents.
    (* 100 (int (registration->payment-amount payment-config registration-details))))
  (get-payment-by-order-number [_ order-number]
    ; TODO
    nil)
  (create-payment-for-registration! [_ tx registration language amount]
    (let [payment-data      (create-payment-data registration language amount)
          paytrail-response (-> (create-paytrail-payment! payment-data)
                                (:body)
                                (json/read-str))]
      (select-keys paytrail-response ["transactionId" "href"])))
  (initialise-payment-on-registration? [_]
    false))

(defmethod ig/init-key :yki.util/payment-helper [_ {:keys [db url-helper payment-config]}]
  (if (:use-new-payments-api? payment-config)
    (->NewPaymentHelper db url-helper payment-config)
    (->LegacyPaymentHelper db url-helper payment-config)))

