(ns yki.handler.evaluation
  (:require [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.handler.routing :as routing]
            [clojure.tools.logging :as log]
            [ring.util.http-response :refer [internal-server-error ok unprocessable-entity]]
            [ring.util.response :refer [response not-found]]
            [ring.util.request]
            [yki.spec :as ys]
            [integrant.core :as ig]))

(defn- evaluation-not-found [evaluation_id]
  (log/info "Evaluation not found with id" evaluation_id)
  (not-found {:success false
              :error "Evaluation not found"}))

(defn- validate-subtests-response [subtests all-subtests]
  (println "Subtests: " subtests)
  (println "All subtests: " all-subtests)
  (cond
    (= true (empty? '(1))) {:success false
                            :error "Minimum of one subtest is required to place an order"}
    :else nil))

(comment (println "Validation result: " (validate-subtests-response ["asd" "dsad"] ["asdasd"])))

(defmethod ig/init-key :yki.handler/evaluation [_ {:keys [db]}]
  (context routing/evaluation-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/evaluation-periods-response
      (ok {:evaluation_periods (evaluation-db/get-upcoming-evaluation-periods db)}))
    (context "/:id" []
      (GET "/" []
        :path-params [id :- ::ys/id]
        :return ::ys/evaluation-period
        (if-let [evaluation (evaluation-db/get-evaluation-period-by-id db id)]
          (ok evaluation)
          (evaluation-not-found id)))
      (POST "/order-formdata" []
        :body [order ::ys/evaluation-order]
        :path-params [id :- ::ys/id]
        :return ::ys/response
        ;; TODO Add validation to check that evaluation period is still open to proceed
        (if-let [evaluation (evaluation-db/get-evaluation-period-by-id db id)]
          (if (empty? (:subtests order))
            (unprocessable-entity {:success false
                                   :error "Minimum of one subtest is required to place an order"})
            (if-let [order-id (evaluation-db/create-evaluation-order! db id order)]
              (let [amount 50]
                (response {:success true}))
              (internal-server-error {:success false
                                      :error "Failed to create a new evaluation order"})))
          (evaluation-not-found id))))))
