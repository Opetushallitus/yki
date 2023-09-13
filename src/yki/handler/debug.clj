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

(defn- validate-emails-for-table [db table get-emails]
  (log/info "Validating emails for table" table)
  (let [nils (atom 0)]
    (doseq [{:keys [id email]} (get-emails db)]
      (if email
        (when-not (s/valid? ::ys/email email)
          (log/info "Invalid email for table" table {:id    id
                                                     :email email}))
        (swap! nils inc)))
    (log/info "Nil emails for table" table ":" @nils)))

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
        (doseq [[table get-emails-fn]
                [["contact" b/get-contact-emails]
                 ["evaluation_order" b/get-evaluation-order-emails]
                 ["exam_session_queue" b/get-exam-session-queue-emails]
                 ["organizer" b/get-organizer-emails]
                 ["participant" b/get-participant-emails]
                 ["quarantine" b/get-quarantine-emails]
                 ["registration" b/get-registration-emails]]]
          (validate-emails-for-table db table get-emails-fn))
        (ok {})))))
