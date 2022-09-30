(ns yki.handler.evaluation
  (:require [clojure.tools.logging :as log]
            [compojure.api.sweet :refer [context GET POST]]
            [integrant.core :as ig]
            [ring.util.http-response :refer [internal-server-error ok unprocessable-entity conflict]]
            [ring.util.response :refer [not-found]]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.handler.routing :as routing]
            [yki.spec :as ys]
            [yki.util.common :as common]
            [yki.util.evaluation-payment-helper :refer [order-id->payment-data use-new-payments-api?]]
            [yki.util.paytrail-payments :refer [sign-string]]))

(defn- evaluation-not-found [evaluation_id]
  (log/info "Evaluation not found with id" evaluation_id)
  (not-found {:success false
              :error   "Evaluation not found"}))

(defn subtest-price-config [amount-config]
  (reduce-kv (fn [m k v]
               (assoc m k (Double/parseDouble v)))
             {}
             amount-config))

(defn- sanitize-order [raw-order]
  (let [text-fields (dissoc raw-order :subtests)
        sanitizer   (partial common/sanitized-string "_")
        sanitized   (update-vals text-fields sanitizer)]
    (merge raw-order sanitized)))

(defmethod ig/init-key :yki.handler/evaluation [_ {:keys [db payment-helper]}]
  {:pre [(some? db) (some? payment-helper)]}
  (context routing/evaluation-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/evaluation-periods-response
      (ok {:evaluation_periods (evaluation-db/get-upcoming-evaluation-periods db)}))

    ; Unauthenticated endpoint
    ; Called when rendering evaluation payment status
    (GET "/order/:id" []
      :path-params [id :- ::ys/id]
      :query-params [lang :- ::ys/language-code]
      ;; :return ::ys/evaluation-response
      (let [lang         (or lang "fi")
            order-data   (evaluation-db/get-evaluation-order-by-id db id)
            payment-data (-> (order-id->payment-data payment-helper id)
                             (select-keys [:state :amount]))]
        (-> order-data
            (merge payment-data)
            (assoc :lang lang))))

    (context "/:id" []
      (GET "/" []
        :path-params [id :- ::ys/id]
        :return ::ys/evaluation-period
        (if-let [evaluation (evaluation-db/get-evaluation-period-by-id db id)]
          (ok evaluation)
          (evaluation-not-found id)))

      (POST "/order" []
        :body [raw-order ::ys/evaluation-order]
        :path-params [id :- ::ys/id]
        :query-params [lang :- ::ys/language-code]
        :return ::ys/evaluation-order-response
        (let [order            (sanitize-order raw-order)
              evaluation       (evaluation-db/get-evaluation-period-by-id db id)
              payment-config   (:payment-config payment-helper)
              price-config     (:amount payment-config)
              missing-config   (fn [subtest] (when (not (contains? price-config (keyword subtest)))
                                               subtest))
              missing-subtests (remove nil? (map missing-config (:subtests order)))
              validation-error (cond
                                 (= evaluation nil) (evaluation-not-found id)
                                 (= (:open evaluation) false) (conflict {:success false
                                                                         :error   (str "Evaluation period is not ongoing for evaluation " id)})
                                 (empty? (:subtests order)) (unprocessable-entity {:success false
                                                                                   :error   "Minimum of one subtest is required to place an order"})
                                 (= (apply distinct? (:subtests order)) false) (unprocessable-entity {:success false
                                                                                                      :error   "Duplicate subtests are not allowed on the same order"})
                                 (not-empty missing-subtests) (unprocessable-entity {:success false
                                                                                     :error   (str "Cannot process subtests without price configuration " (apply str (interpose " " missing-subtests)))})
                                 :else nil)]
          (if (some? validation-error)
            validation-error
            (if-let [order-id (evaluation-db/create-evaluation-order! db id order)]
              (let [subtest-price     (fn [subtest] (get (subtest-price-config price-config) (keyword subtest)))
                    final-price       (->> order
                                           :subtests
                                           (map subtest-price)
                                           (reduce (fn [^BigDecimal acc ^double val]
                                                     (.add acc (BigDecimal/valueOf val))) BigDecimal/ZERO))
                    init-payment-data {:evaluation_order_id order-id
                                       :lang                (or lang "fi")
                                       :amount              final-price}]
                (evaluation-db/create-evaluation-payment! db payment-helper init-payment-data)
                (if (use-new-payments-api? payment-helper)
                  (ok {:evaluation_order_id          order-id
                       :use_new_payments_integration (use-new-payments-api? payment-helper)
                       :signature                    (sign-string (:payment-config payment-helper) (str order-id))})
                  (ok {:evaluation_order_id          order-id})))
              (internal-server-error {:success false
                                      :error   "Failed to create a new evaluation order"}))))))))
