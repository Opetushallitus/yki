(ns yki.handler.debug
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [compojure.api.sweet :refer [api context GET POST]]
    [integrant.core :as ig]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.http-response :refer [ok]]
    [yki.boundary.debug :as b]
    [yki.boundary.codes :as codes]
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

(defn post-code->post-office [{:keys [metadata]}]
  (->> metadata
       (filter #(= "FI" (:kieli %)))
       (first)
       (:nimi)))

(defmethod ig/init-key :yki.handler/debug [_ {:keys [access-log auth db url-helper]}]
  {:pre [(some? access-log)
         (some? auth)
         (some? db)
         (some? url-helper)]}
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
        (ok {}))
      (POST "/fix-post-office-data" _
        :query-params [exam-date-id :- ::ys/id
                       do-update :- boolean?]
        (log/info "Request to fix post office data" {:exam-date-id exam-date-id})
        (let [post-codes        (codes/get-codes url-helper "posti")
              zip->office       (->> post-codes
                                     (map (juxt :koodiArvo post-code->post-office))
                                     (into {}))
              post-codes-to-fix (->> (b/get-registrations-with-same-zip-code-and-post-office db exam-date-id)
                                     (map :zip)
                                     (into #{}))]
          (doseq [zip post-codes-to-fix]
            (if-let [post-office (zip->office zip)]
              (let [updated (b/fix-post-office-for-zip-code! db exam-date-id zip post-office do-update)]
                (log/info (if do-update "Updating" "NOT UPDATING, BUT LOGGING") updated "registrations with zip code" zip "and post-office" post-office))
              (log/warn "No post office found for zip code" zip))))
        (ok {})))))
