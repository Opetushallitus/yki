(ns yki.handler.evaluation
  (:require [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.handler.routing :as routing]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [internal-server-error ok unprocessable-entity conflict see-other found]]
            [ring.util.response :refer [response not-found]]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defn- evaluation-not-found [evaluation_id]
  (log/info "Evaluation not found with id" evaluation_id)
  (not-found {:success false
              :error "Evaluation not found"}))

(defn subtest-price-config [amount-config]
  (reduce-kv (fn [m k v]
               (assoc m k (Double/parseDouble v)))
             {}
             amount-config))

(defmethod ig/init-key :yki.handler/evaluation [_ {:keys [db payment-config]}]
  {:pre [(some? db) (some? payment-config)]}
  (context routing/evaluation-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/evaluation-periods-response
      (ok {:evaluation_periods (evaluation-db/get-upcoming-evaluation-periods db)}))
        ;; TODO Add proper response
    (GET "/order/:id" []
      :path-params [id :- ::ys/id]
      (ok (evaluation-db/get-evaluation-order-by-id db id)))

    (context "/:id" []
      (GET "/" []
        :path-params [id :- ::ys/id]
        :return ::ys/evaluation-period
        (if-let [evaluation (evaluation-db/get-evaluation-period-by-id db id)]
          (ok evaluation)
          (evaluation-not-found id)))
      (POST "/order" []
        :body [order ::ys/evaluation-order]
        :path-params [id :- ::ys/id]
        ;;:return ::ys/response
        ;; TODO Add proper response
        (let [evaluation         (evaluation-db/get-evaluation-period-by-id db id)
              price-config       (:amount payment-config)
              missing-config     (fn [subtest] (when (not (contains? price-config (keyword subtest)))
                                                 subtest))
              missing-subtests   (remove nil? (map missing-config (:subtests order)))
              validation-error   (cond
                                   (= evaluation nil)                       (evaluation-not-found id)
                                   (= (:open evaluation) false)             (conflict             {:success false
                                                                                                   :error (str "Evaluation period is not ongoing for evaluation" id)})
                                   (empty? (:subtests order))               (unprocessable-entity {:success false
                                                                                                   :error "Minimum of one subtest is required to place an order"})
                                   (= (distinct? (:subtests order)) false)  (unprocessable-entity {:success false
                                                                                                   :error "Duplicate subtests are not allowed on the same order"})
                                   (not-empty missing-subtests)             (unprocessable-entity {:success false
                                                                                                   :error (str "Cannot process subtests without price configuration " (apply str (interpose " " missing-subtests)))})
                                   :else nil)]
          (if (some? validation-error)
            validation-error
            (if-let [order-id (evaluation-db/create-evaluation-order! db id order)]
              (let [lang              "fi"
                    subtest-price     (fn [subtest] (get (subtest-price-config price-config) (keyword subtest)))
                    final-price       (->> order
                                           :subtests
                                           (map subtest-price)
                                           (reduce +)
                                           (bigdec))
                    init-payment-data {:evaluation_order_id order-id
                                       :lang                lang
                                       :amount              final-price}
                    payment           (evaluation-db/create-evaluation-payment! db init-payment-data)]
                (ok {:evaluation_order_id order-id}))
              (internal-server-error {:success false
                                      :error "Failed to create a new evaluation order"}))))))))
