(ns yki.handler.evaluation
  (:require [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.handler.routing :as routing]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [internal-server-error ok unprocessable-entity see-other found]]
            [ring.util.response :refer [response not-found]]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defn- evaluation-not-found [evaluation_id]
  (log/info "Evaluation not found with id" evaluation_id)
  (not-found {:success false
              :error "Evaluation not found"}))

(defn subtest-config [amount-config]
  (reduce-kv (fn [m k v]
               (assoc m k (Double/parseDouble v)))
             {}
             amount-config))

(defmethod ig/init-key :yki.handler/evaluation [_ {:keys [db auth access-log url-helper payment-config]}]
  {:pre [(some? db) (some? auth) (some? access-log) (some? url-helper) (some? payment-config)]}
  (context routing/evaluation-root []
    :coercion :spec
    :middleware [auth access-log]
    (GET "/" []
      :return ::ys/evaluation-periods-response
      (ok {:evaluation_periods (evaluation-db/get-upcoming-evaluation-periods db)}))
    (GET "/order/:id" []
      :path-params [id :- ::ys/id]
      (ok (evaluation-db/get-finished-evaluation-order-by-id db id)))
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
        ;; TODO Add validation to check that evaluation period is still open to proceed
        (if-let [evaluation (evaluation-db/get-evaluation-period-by-id db id)]
          (if (empty? (:subtests order))
            (unprocessable-entity {:success false
                                   :error "Minimum of one subtest is required to place an order"})
            (if-let [order-id (evaluation-db/create-evaluation-order! db id order)]
              (let [lang              "fi"
                    subtest-price     (fn [subtest] (get (subtest-config (:amount payment-config)) (keyword subtest)))
                    final-price       (->> order
                                           :subtests
                                           (map subtest-price)
                                           (reduce +)
                                           (bigdec))
                    init-payment-data {:evaluation_order_id order-id
                                       :lang                lang
                                       :amount              final-price}
                    payment           (evaluation-db/create-evaluation-payment! db init-payment-data)]
                (ok {:evaluation_order_id (:id payment)}))
              (internal-server-error {:success false
                                      :error "Failed to create a new evaluation order"})))
          (evaluation-not-found id))))))
