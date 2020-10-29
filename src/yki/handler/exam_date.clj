(ns yki.handler.exam-date
  (:require [compojure.api.sweet :refer [context GET DELETE POST]]
            [yki.boundary.exam-date-db :as exam-date-db]
            [ring.util.http-response :refer [ok not-found]]
            [yki.spec :as ys]
            [yki.handler.routing :as routing]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/exam-date [_ {:keys [db]}]
  (context routing/exam-date-api-root []
    :coercion :spec
    (GET "/" []
      :return ::ys/exam-date-response
      (ok {:dates (exam-date-db/get-exam-dates db)}))
      (context "/:id" []
        (POST "/post-admission-end-date" []
          :body [end-date ::ys/post-admission-end-date-update]
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (nil? (:post_admission_end_date end-date))
              (not-found {:success false :error "End date can not be null"})
              (if (exam-date-db/update-post-admission-end-date! db id (:post_admission_end_date end-date))
                  (ok {:success true})
                  (not-found {:success false :error "Exam date not found"}))))
        (DELETE "/post-admission-end-date" []
          :path-params [id :- ::ys/id]
          :return ::ys/response
          (if (exam-date-db/delete-post-admission-end-date! db id)
              (ok {:success true})
              (not-found {:success false :error "Exam date not found"}))))))
