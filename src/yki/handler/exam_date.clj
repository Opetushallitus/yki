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
  (let [current                (exam-date-db/get-exam-date-by-id db id)
        is-configured?         (and
                                (some? (:post_admission_start_date current))
                                (some? (:post_admission_end_date current)))]
    (cond
      (= current nil)          (do (log/error "Could not find exam date with id " id)
                                   (not-found
                                    {:success false :error "Exam date not found"}))
      (= false is-configured?) (do (log/error "Exam date " id " post admission start and end dates are not configured")
                                   (conflict
                                    {:success false :error "Post admission start and end dates are not configured"}))
      :else                    (if (exam-date-db/toggle-post-admission! db id enabled)
                                 (ok {:success true})
                                 (do (log/error "Error occured when trying to enable post admission for exam date " id)
                                     (internal-server-error {:success false :error "Could not enable post admission for the exam date"}))))))

(defmethod ig/init-key :yki.handler/exam-date [_ {:keys [db]}]
  (fn [oid]
    (context "/" []
      (GET "/" []
        :return ::ys/exam-date-response
        (ok {:dates (exam-date-db/get-organizer-exam-dates db)}))
      (POST "/" request
        :body [exam-date ::ys/exam-date-type]
        :return ::ys/id-response
        (log/info "Creating a new exam date")
        (let [{exam :exam_date
               reg-start :registration_start_date
               reg-end :registration_end_date} exam-date
              existing-exam-dates (exam-date-db/get-exam-dates-by-date db (c/from-long exam))]
          (cond
            (= false (empty? existing-exam-dates))          (do (log/error "Exam date " exam " already exists")
                                                                (conflict {:success false :error "Exam date already exists"}))
            (is-first-date-before-second reg-end reg-start) (do (log/error "Registeration start date " reg-start " has to be before it's end date " reg-end)
                                                                (conflict {:success false :error "Registeration start date has to be before it's end date"}))
            (is-first-date-before-second exam reg-end)      (do (log/error "Registeration end date " reg-end " needs to be before exam date " exam)
                                                                (conflict {:success false :error "Registeration end date needs to be before exam date"}))
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
                (do (log/error "Error occured when attempting to create a new exam date " exam)
                    (internal-server-error
                     {:success false :error "Could not create an exam date"})))))))

      (context "/:id" []
        (GET "/" []
          :path-params [id :- ::ys/id]
          :return ::ys/single-exam-date-response
          (if-let [exam-date (exam-date-db/get-exam-date-by-id db id)]
            (ok {:date exam-date})
            (do (log/error "Could not find an exam date with id " id)
                (not-found {:success false
                            :error "Exam date not found"}))))

        (DELETE "/" request
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (log/info "Deleting exam date " id)
          (let [exam-date (exam-date-db/get-exam-date-by-id db id)
                session-count (:count (exam-date-db/get-exam-date-session-count db id))]
            (cond
              (= exam-date nil)   (do (log/error "Could not find an exam date " id " to delete")
                                      (not-found {:success false :error "Exam date not found"}))
              (> session-count 0) (do (log/error "Cannot delete an exam date " id " because it has exam sessions assigned to it")
                                      (conflict {:success false :error "Cannot delete exam date with assigned exam sessions"}))
              :else (if (exam-date-db/delete-exam-date! db id)
                      (do
                        (audit-log/log {:request request
                                        :target-kv {:k audit-log/exam-date
                                                    :v id}
                                        :change {:type audit-log/delete-op}})
                        (ok {:success true}))
                      (do (log/error "Error occured when attempting to delete exam date " id)
                          (internal-server-error {:success false
                                                  :error "Could not delete the exam date"}))))))

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
              (= exam-date nil) (do (log/error "Could not find an exam date " id " to modify")
                                    (not-found {:success false :error "Exam date not found"}))
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
                (do (log/error "Error occured when modifying languages in exam date " id)
                    (internal-server-error {:success false :error "Could not create exam date languages"}))))))

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
                (= current nil)                        (not-found {:success false :error "Exam date not found"})
                (not-empty post-admission-date-errors) (do (log/error "Conflict in post admission handling for exam date " id ": " (first post-admission-date-errors))
                                                           (conflict (first post-admission-date-errors)))
                :else (if (exam-date-db/update-post-admission-details! db id post-admission)
                        (ok {:success true})
                        (do (log/error "Error occured when attempting to configure post admission for exam date " id)
                            (internal-server-error {:success false :error "Could not configure post admission"}))))))

          (POST "/enable" request
            :path-params [id :- ::ys/id]
            :return ::ys/response
            (toggle-post-admission db id true))

          (POST "/disable" request
            :path-params [id :- ::ys/id]
            :return ::ys/response
            (toggle-post-admission db id false)))))))
