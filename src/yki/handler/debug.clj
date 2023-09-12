(ns yki.handler.debug
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok]]
    [yki.boundary.debug :as b]
    [yki.handler.routing :as routing]
    [yki.spec :as ys]))

(defn- validate-emails-for-table [db table get-emails nilable?]
  (log/info "Validating emails for table" table)
  (doseq [{:keys [id email]} (get-emails db)]
    (if nilable?
      (when (and email (not (s/valid? ::ys/email email)))
        (log/info "Invalid email for table" table {:id    id
                                                   :email email}))
      (when-not (s/valid? ::ys/email email)
        (log/info "Invalid email for table" table {:id    id
                                                   :email email})))))

(defmethod ig/init-key :yki.handler/debug [_ {:keys [access-log auth db]}]
  {:pre [(some? access-log)
         (some? auth)
         (some? db)]}
  (api
    (context routing/debug-root []
      :coercion :spec
      :no-doc true
      :middleware [auth access-log wrap-params]
      (POST "/emails/validate" _
        (doseq [[table get-emails-fn nilable?]
                [["contact" b/get-contact-emails true]
                 ["evaluation_order" b/get-evaluation-order-emails false]
                 ["exam_session_queue" b/get-exam-session-queue-emails false]
                 ["organizer" b/get-organizer-emails false]
                 ["participant" b/get-participant-emails false]
                 ["quarantine" b/get-quarantine-emails true]
                 ["registration" b/get-registration-emails true]]]
          (validate-emails-for-table db table get-emails-fn nilable?))
        (ok {})))))
