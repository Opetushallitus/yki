(ns yki.boundary.exam-session-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [yki.boundary.exam-session-db :as exam-session-db]
    [yki.handler.base-test :as base]
    [yki.embedded-db :as embedded-db]))

(use-fixtures :each embedded-db/with-postgres embedded-db/with-migration embedded-db/with-transaction)

(defn- exam-session-id->queue-count [id]
  (-> (str "SELECT COUNT(*) FROM exam_session_queue WHERE exam_session_id=" id ";")
      (base/select-one)
      (:count)))

(deftest exam-session-queue-test
  ; Insert base data
  (let [organizer-oid "1.2.3.4.5"]
    (base/insert-exam-dates)
    ; Ensure registration period is open, as otherwise enqueing is not possible.
    (base/execute! "UPDATE exam_date SET registration_start_date=(current_date - interval '1 DAY'), registration_end_date=(current_date + interval '1 DAY') WHERE id IN (1,2);") ;
    (base/insert-organizer organizer-oid)
    (base/insert-exam-session 1 organizer-oid 10)
    (base/insert-exam-session 2 organizer-oid 10))
  (testing "initially exam_session_queue is empty"
    (is (= 0 (exam-session-id->queue-count 1)))
    (is (= 0 (exam-session-id->queue-count 2))))
  (testing "emails can be added to queue"
    (exam-session-db/add-to-exam-session-queue! (base/db) "foo@bar.test" "FI" 1)
    (exam-session-db/add-to-exam-session-queue! (base/db) "foo@bar.test" "FI" 2)
    (is (= 1 (exam-session-id->queue-count 1)))
    (is (= 1 (exam-session-id->queue-count 2))))
  (testing "deleting old exam_session_queue entries"
    (testing "previous results are still available"
      (is (= 1 (exam-session-id->queue-count 1)))
      (is (= 1 (exam-session-id->queue-count 2))))
    (testing "does nothing if the relevant exam sessions are recent enough"
      ; Set both exam sessions to the future
      (base/execute! "UPDATE exam_date SET exam_date=(current_date + interval '1 WEEK') WHERE id IN (1,2);")
      (exam-session-db/remove-old-entries-from-exam-session-queue! (base/db))
      (is (= 1 (exam-session-id->queue-count 1)))
      (is (= 1 (exam-session-id->queue-count 2)))
      ; Exam date for session 2 now at three weeks ago
      (base/execute! "UPDATE exam_date SET exam_date=(current_date - interval '3 WEEKS') WHERE id=2;")
      (exam-session-db/remove-old-entries-from-exam-session-queue! (base/db))
      (is (= 1 (exam-session-id->queue-count 1)))
      (is (= 1 (exam-session-id->queue-count 2))))
    (testing "removes entries if exam sessions were held over a month ago"
      ; Exam date for session 2 now over a month ago
      (base/execute! "UPDATE exam_date SET exam_date=(current_date - interval '1 MONTH' - interval '1 DAY') WHERE id=2;")
      (exam-session-db/remove-old-entries-from-exam-session-queue! (base/db))
      (is (= 1 (exam-session-id->queue-count 1)))
      (is (= 0 (exam-session-id->queue-count 2))))))
