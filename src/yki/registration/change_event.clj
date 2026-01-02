(ns yki.registration.change-event
  (:require [clojure.set :as set]))

(defn registration->change-event [registration]
  (-> registration
      (select-keys [:id :kind :state :exam_session_id :original_exam_session_id])
      (set/rename-keys {:id    :registration_id
                        :kind  :registration_kind
                        :state :registration_state})))
