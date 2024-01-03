(ns yki.boundary.debug
  (:require
    [jeesql.core :refer [require-sql]])
  (:import [duct.database.sql Boundary]))

(require-sql ["yki/debug.sql" :as q])

(defprotocol EmailDebug
  (get-contact-emails [db])
  (update-contact-email! [db id email])
  (get-evaluation-order-emails [db])
  (update-evaluation-order-email! [db id email])
  (get-exam-session-queue-emails [db])
  (update-exam-session-queue-email! [db id email])
  (get-organizer-emails [db])
  (update-organizer-email! [db id email])
  (get-participant-emails [db])
  (update-participant-email! [db id email])
  (get-quarantine-emails [db])
  (update-quarantine-email! [db id email])
  (get-registration-emails [db])
  (update-registration-email! [db id email]))

(extend-protocol EmailDebug
  Boundary
  (get-contact-emails [db]
    (q/select-contact-emails (:spec db)))
  (update-contact-email! [db id email]
    (q/update-contact-email! (:spec db) {:id id :email email}))
  (get-evaluation-order-emails [db]
    (q/select-evaluation-order-emails (:spec db)))
  (update-evaluation-order-email! [db id email]
    (q/update-evaluation-order-email! (:spec db) {:id id :email email}))
  (get-exam-session-queue-emails [db]
    (q/select-exam-session-queue-emails (:spec db)))
  (update-exam-session-queue-email! [db id email]
    (q/update-exam-session-queue-email! (:spec db) {:id id :email email}))
  (get-organizer-emails [db]
    (q/select-organizer-emails (:spec db)))
  (update-organizer-email! [db id email]
    (q/update-organizer-email! (:spec db) {:id id :email email}))
  (get-participant-emails [db]
    (q/select-participant-emails (:spec db)))
  (update-participant-email! [db id email]
    (q/update-participant-email! (:spec db) {:id id :email email}))
  (get-quarantine-emails [db]
    (q/select-quarantine-emails (:spec db)))
  (update-quarantine-email! [db id email]
    (q/update-quarantine-email! (:spec db) {:id id :email email}))
  (get-registration-emails [db]
    (q/select-registration-emails (:spec db)))
  (update-registration-email! [db id email]
    (q/update-registration-email! (:spec db) {:id id :email email})))

(defprotocol PostOfficeDebug
  (get-registrations-with-same-zip-code-and-post-office [db exam-date])
  (fix-post-office-for-zip-code! [db exam-date-id zip-code post-office do-update?]))

(extend-protocol PostOfficeDebug
  Boundary
  (get-registrations-with-same-zip-code-and-post-office [db exam-date-id]
    (q/select-registrations-with-same-zip-code-and-post-office
      (:spec db) {:exam_date_id exam-date-id}))
  (fix-post-office-for-zip-code! [db exam-date-id zip-code post-office do-update?]
    (if do-update?
      (q/update-post-office-for-zip-code!
        (:spec db)
        {:exam_date_id exam-date-id
         :zip          zip-code
         :post_office  post-office})
      (-> (q/select-count-of-post-offices-to-replace
            (:spec db)
            {:exam_date_id exam-date-id
             :zip          zip-code
             :post_office  post-office})
          (first)
          (:count)))))
