(ns yki.handler.exam-date
  (:require [compojure.api.sweet :refer [context GET DELETE POST PUT]]
            [yki.boundary.exam-date-db :as exam-date-db]
            [ring.util.http-response :refer [ok not-found conflict internal-server-error]]
            [yki.util.audit-log :as audit-log]
            [clojure.tools.logging :as log]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [integrant.core :as ig]
            [clojure.set :as set]))

(defn is-first-date-before-second [current-date post-date]
  (if (nil? post-date) false (let [to-date (fn [d] (c/from-long d))]
                               (t/before? (to-date current-date) (to-date post-date)))))

(defn toggle-post-admission [db id enabled]
  (let [current (exam-date-db/get-exam-date-by-id db id)
        is-configured? (and
                        (some? (:post_admission_start_date current))
                        (some? (:post_admission_end_date current)))]
    (cond
      (= current nil) (not-found
                       {:success false :error "Exam date not found"})
      (= false is-configured?) (conflict
                                {:success false :error "Post admission start and end dates are not configured"})
      :else (if (exam-date-db/toggle-post-admission! db id enabled)
              (ok {:success true})
              (internal-server-error {:success false :error "Could not enable post admission for the exam date"})))))

(defmethod ig/init-key :yki.handler/exam-date [_ {:keys [db]}]
  (fn [oid]
    (context "/" []
      (GET "/" []
        :return ::ys/exam-date-response
        (ok {:dates (exam-date-db/get-organizer-exam-dates db)}))
      (POST "/" request
        :body [exam-date ::ys/exam-date-type]
        :return ::ys/id-response
        (let [{exam :exam_date
               reg-start :registration_start_date
               reg-end :registration_end_date} exam-date
              existing-exam-dates (exam-date-db/get-exam-dates-by-date db (c/from-long exam))]
          (cond
            (= false (empty? existing-exam-dates)) (conflict {:success false :error "Exam date already exists"})
            (is-first-date-before-second reg-end reg-start) (conflict {:success false :error "Registeration start date has to be before it's end date"})
            (is-first-date-before-second exam reg-end) (conflict {:success false :error "Registeration end date needs to be before exam date"})
            :else
            (let [exam-date-id (exam-date-db/create-exam-date! db exam-date)]
              (if exam-date-id
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/exam-date
                                              :v exam-date-id}
                                  :change {:type audit-log/create-op
                                           :new exam-date}})
                  (ok {:id exam-date-id}))
                (internal-server-error
                 {:success false :error "Could not create an exam date"}))))))

      (context "/:id" []
        (GET "/" []
          :path-params [id :- ::ys/id]
          :return ::ys/single-exam-date-response
          (if-let [exam-date (exam-date-db/get-exam-date-by-id db id)]
            (ok {:date exam-date})
            (not-found {:success false
                        :error "Exam date not found"})))

        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (let [exam-date (exam-date-db/get-exam-date-by-id db id)
                session-count (:count (exam-date-db/get-exam-date-session-count db id))]
            (cond
              (= exam-date nil) (not-found {:success false :error "Exam date not found"})
              (> session-count 0) (conflict {:success false :error "Cannot delete exam date with assigned exam sessions"})
              :else (if (exam-date-db/delete-exam-date! db id)
                      (do
                        (audit-log/log {:request request
                                        :target-kv {:k audit-log/exam-date
                                                    :v id}
                                        :change {:type audit-log/delete-op}})
                        (ok {:success true}))
                      (internal-server-error {:success false
                                              :error "Could not delete the exam date"})))))

        (POST "/languages" request
          :path-params [id :- ::ys/id]
          :body [languages ::ys/languages]
          :return ::ys/response
          (let [exam-date (exam-date-db/get-exam-date-by-id db id)
                exam-date-languages (vec (:languages exam-date))
                new-languages (set/difference (set languages) (set exam-date-languages))
                removable-languages (set/difference (set exam-date-languages) (set languages))]

            (when (not-empty new-languages) (log/info "Adding languages to exam date " id ": " new-languages))
            (when (not-empty removable-languages) (log/info "Removing languages from exam date " id ": " removable-languages))

            (cond
              (= exam-date nil) (not-found {:success false :error "Exam date not found"})
              :else
              (if (exam-date-db/create-and-delete-exam-date-languages! db id new-languages removable-languages)
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/exam-date
                                              :v id}
                                  :change {:type audit-log/update-op
                                           :old exam-date
                                           :new (assoc exam-date :languages (concat (:languages exam-date) languages))}})
                  (ok {:success true}))
                (internal-server-error {:success false :error "Could not create exam date languages"})))))

        (DELETE "/languages" request
          :path-params [id :- ::ys/id]
          :body [languages ::ys/languages]
          :return ::ys/response
          (let [exam-date (exam-date-db/get-exam-date-by-id db id)
                session-count (:count (exam-date-db/get-exam-date-session-count db id))]
            (cond
              (= exam-date nil) (not-found {:success false :error "Exam date not found"})
              (> session-count 0) (conflict {:success false :error "Cannot delete languages from exam date with assigned exam sessions"})
              :else
              (if (exam-date-db/delete-exam-date-languages! db id languages)
                (do
                  (audit-log/log {:request request
                                  :target-kv {:k audit-log/exam-date
                                              :v id}
                                  :change {:type audit-log/update-op
                                           :old exam-date
                                           :new (assoc exam-date :languages (:languages (exam-date-db/get-exam-date-by-id db id)))}})
                  (ok {:success true}))
                (internal-server-error {:success false :error "Could not delete exam date languages"})))))

        (context "/post-admission" []
          (POST "/" request
            :path-params [id :- ::ys/id]
            :body [post-admission ::ys/exam-date-post-admission-update]
            :return ::ys/response
            (let [{post-start :post_admission_start_date post-end :post_admission_end_date} post-admission
                  current (exam-date-db/get-exam-date-by-id db id)
                  post-admission-date-errors (remove nil?
                                                     (vector
                                                      (when (is-first-date-before-second post-end post-start)
                                                        {:success false :error "Post admission start date has to be before it's end date"})
                                                      (when (is-first-date-before-second post-start (:registration_end_date current))
                                                        {:success false :error "Post admission start date has to be after registration has ended"})
                                                      (when (is-first-date-before-second (:exam_date current) post-end)
                                                        {:success false :error "Post admission end date has to be before exam date"})))]
              (cond
                (= current nil) (not-found
                                 {:success false :error "Exam date not found"})
                (not-empty post-admission-date-errors) (conflict (first post-admission-date-errors))
                :else (if (exam-date-db/update-post-admission-details! db id post-admission)
                        (ok {:success true})
                        (internal-server-error {:success false :error "Could not delete the exam date"})))))

          (POST "/enable" request
            :path-params [id :- ::ys/id]
            :return ::ys/response
            (toggle-post-admission db id true))

          (POST "/disable" request
            :path-params [id :- ::ys/id]
            :return ::ys/response
            (toggle-post-admission db id false)))))))
