(ns yki.boundary.yki-v2
  (:require
   [clojure.data.json :as json]
   [org.httpkit.client :as http]
   [yki.util.common :as common]))

(defn send-request [{:keys [endpoint token]} oid method uri query-params body-params headers]
  (let [auth     (when oid {"Authorization"
                             (str oid ":" (common/sha256-hash (str oid token)))})
        opts     {:method       method
                  :url          (str endpoint uri)
                  :body         (json/write-str body-params)
                  :headers      (merge auth headers)
                  :query-params query-params}
        response @(http/request opts)]
    response))

(defn proxy-request [proxy-params {:keys [request-method uri body-params query-params headers] :as request}]
  (let [oid      (get-in request [:session :identity :oid])
        response (send-request proxy-params oid request-method uri query-params body-params headers)]
    {:status  (:status response)
     :body    (:body response)
     :headers {"content-type" (:content-type (:headers response))}}))

(defn init-registration [proxy-params exam-session-id to-queue partial-exam-type person-oid participant-id strong-auth]
  (let [response (send-request proxy-params
                               person-oid
                               :post
                               "/api/public/registration/init"
                               nil
                               {:exam_session_id   exam-session-id
                                :to_queue          to-queue
                                :partial_exam_type partial-exam-type
                                :person_oid        person-oid
                                :participant_id    participant-id
                                :strong_auth       strong-auth}
                               {"content-type" "application/json"})]
    {:status  (:status response)
     :body    (:body response)
     :headers {"content-type" (:content-type (:headers response))}}))
