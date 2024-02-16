(ns yki.handler.exam-date
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [compojure.api.sweet :refer [context GET POST PUT DELETE]]
            [integrant.core :as ig]
            [ring.util.http-response :refer [ok not-found conflict internal-server-error]]
            [yki.boundary.exam-date-db :as exam-date-db]
            [yki.boundary.evaluation-db :as evaluation-db]
            [yki.spec :as ys]
            [yki.util.audit-log :as audit-log]))

(defn is-invalid-period [period]
  (not= period (sort period)))

(defmethod ig/init-key :yki.handler/exam-date [_ {:keys [db]}]
  {:pre [(some? db)]}
  (fn [_oid]
    (context "/" []
      (GET "/" []
        :query-params [{from :- ::ys/date-type nil} {days :- ::ys/days nil}]
        :return ::ys/exam-date-response
        (let [from-date     (if from (c/from-long from) (t/now))
              history-date  (if days (-> from-date
                                         (t/minus (t/days days))) from-date)
              new-from-date (f/unparse (f/formatter "yyyy-MM-dd") history-date)
              dates         (exam-date-db/get-organizer-exam-dates db new-from-date)]
          (ok {:dates dates})))

      (POST "/" request
        :body [exam-date ::ys/exam-date-type]
        :return ::ys/id-response
        (let [{date      :exam_date
               reg-start :registration_start_date
               reg-end   :registration_end_date} exam-date

              period        [reg-start reg-end date]
              existing-date (first (exam-date-db/get-exam-dates-by-date db (c/from-long date)))]
          (cond
            (is-invalid-period period) (do (log/error "Invalid date period for exam date create" period)
                                           (conflict {:success false
                                                      :error   "Invalid date period for exam date create"}))

            (not (nil? existing-date)) (do (log/error "Exam date" date "already exists")
                                           (conflict {:success false
                                                      :error   "Exam date already exists"}))
            :else
            (do (log/info "Creating a new exam date")
                (if-let [id (exam-date-db/create-exam-date! db exam-date)]
                  (do
                    (audit-log/log {:request   request
                                    :target-kv {:k audit-log/exam-date
                                                :v id}
                                    :change    {:type audit-log/create-op
                                                :new  exam-date}})
                    (ok {:id id}))
                  (do (log/error "Creating exam date failed")
                      (internal-server-error
                        {:success false
                         :error   "Creating exam date failed"})))))))

      (context "/:id" []
        (GET "/" []
          :path-params [id :- ::ys/id]
          :return ::ys/single-exam-date-response
          (if-let [exam-date (exam-date-db/get-exam-date-by-id db id)]
            (ok {:date exam-date})
            (do (log/error "Could not get exam date" id)
                (not-found {:success false
                            :error   "Exam date not found"}))))

        (PUT "/" request
          :path-params [id :- ::ys/id]
          :body [exam-date-update ::ys/exam-date-type]
          :return ::ys/response
          (if-let [exam-date (exam-date-db/get-exam-date-by-id db id)]
            (let [{date       :exam_date
                   reg-start  :registration_start_date
                   reg-end    :registration_end_date
                   post-start :post_admission_start_date
                   post-end   :post_admission_end_date} exam-date-update

                  period              [reg-start reg-end post-start post-end date]
                  existing-date       (first (exam-date-db/get-exam-dates-by-date db (c/from-long date)))

                  original-languages  (set (:languages exam-date))
                  provided-languages  (set (:languages exam-date-update))
                  new-languages       (set/difference provided-languages original-languages)
                  removable-languages (set/difference original-languages provided-languages)

                  is-date-change      (not= date (:exam_date exam-date))
                  is-language-change  (seq (concat new-languages removable-languages))
                  has-exam-sessions   (> (:count (exam-date-db/get-exam-date-session-count db id)) 0)]
              (cond
                (is-invalid-period (filter some? period)) (do (log/error "Invalid date period for exam date update" period)
                                                              (conflict {:success false
                                                                         :error   "Invalid date period for exam date update"}))

                (and existing-date (not= id (:id existing-date)))
                (do (log/error "Another exam date with date" date "already exists")
                    (conflict {:success false
                               :error   "Another exam date with the same date already exists"}))

                (and has-exam-sessions (or is-date-change is-language-change))
                (do (log/error "Changing date or languages for an exam date with exam sessions not allowed")
                    (conflict {:success false
                               :error   "Changing date or languages for an exam date with exam sessions not allowed"}))
                :else
                (do (log/info "Updating exam date" id)
                    (when (seq new-languages)
                      (log/info "Adding languages to exam date" id ":" new-languages))
                    (when (seq removable-languages)
                      (log/info "Removing languages from exam date " id ": " removable-languages))
                    (if (exam-date-db/update-exam-date! db id exam-date-update new-languages removable-languages)
                      (let [updated-exam-date (exam-date-db/get-exam-date-by-id db id)]
                        (audit-log/log {:request   request
                                        :target-kv {:k audit-log/exam-date
                                                    :v id}
                                        :change    {:type audit-log/update-op
                                                    :old  exam-date
                                                    :new  updated-exam-date}})
                        (ok {:success true}))
                      (do (log/error "Updating exam date" id "failed")
                          (internal-server-error {:success false
                                                  :error   "Updating exam date failed"}))))))

            (do (log/error "Exam date with id" id "not found")
                (not-found
                  {:success false
                   :error   "Exam date not found"}))))

        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (cond
            (nil? (exam-date-db/get-exam-date-by-id db id))
            (do (log/error "Exam date with id" id "not found")
                (not-found
                  {:success false
                   :error   "Exam date not found"}))

            (> (:count (exam-date-db/get-exam-date-session-count db id)) 0)
            (do (log/error "Cannot delete exam date" id "because it has exam sessions assigned to it")
                (conflict {:success false
                           :error   "Cannot delete exam date with assigned exam sessions"}))
            :else
            (do (log/info "Deleting exam date" id)
                (if (exam-date-db/delete-exam-date! db id)
                  (do (audit-log/log {:request   request
                                      :target-kv {:k audit-log/exam-date
                                                  :v id}
                                      :change    {:type audit-log/delete-op}})
                      (ok {:success true}))
                  (do (log/error "Deleting exam date" id "failed")
                      (internal-server-error {:success false
                                              :error   "Deleting exam date failed"}))))))

        (POST "/evaluation" _
          :path-params [id :- ::ys/id]
          :body [evaluation ::ys/exam-date-evaluation]
          :return ::ys/response
          (if-let [exam-date (exam-date-db/get-exam-date-by-id db id)]
            (let [{:keys [evaluation_start_date evaluation_end_date]} evaluation
                  exam-date-languages (exam-date-db/get-exam-date-languages db id)
                  evaluations         (evaluation-db/get-evaluation-periods-by-exam-date-id db id)
                  period              [(:exam_date exam-date) evaluation_start_date evaluation_end_date]]
              (cond
                (empty? exam-date-languages) (do (log/error "Could not find language options for exam date" id)
                                                 (conflict {:success false
                                                            :error   "Could not find language options for exam date"}))

                (seq evaluations) (do (log/error "Evaluation configuration for exam date" id "already exists")
                                      (conflict {:success false
                                                 :error   "Evaluation configuration for exam date already exists"}))

                (is-invalid-period period) (do (log/error "Invalid date period for exam date evaluation" period)
                                               (conflict {:success false
                                                          :error   "Invalid date period for exam date evaluation"}))
                :else
                (if (evaluation-db/create-evaluation! db exam-date-languages evaluation)
                  (ok {:success true})
                  (do (log/error "Creating evaluation period for exam date" id "failed")
                      (internal-server-error {:success false
                                              :error   "Creating evaluation period for exam date failed"})))))
            (do (log/error "Exam date with id" id "not found")
                (not-found
                  {:success false
                   :error   "Exam date not found"}))))))))
