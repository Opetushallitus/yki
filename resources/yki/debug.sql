-- name: select-participant-onr-data
SELECT participant_onr.*, last_exam_session.exam_date, registration_evaluation.state AS evaluation_state
FROM participant_onr
LEFT JOIN (
  SELECT DISTINCT ON (r.participant_id)
    r.participant_id,
    r.id AS registration_id,
    ed.exam_date
  FROM registration r
  JOIN exam_session es ON es.id = r.exam_session_id
  JOIN exam_date ed ON ed.id = es.exam_date_id
  ORDER BY r.participant_id, ed.exam_date DESC, r.id DESC
) last_exam_session
ON last_exam_session.participant_id = participant_onr.participant_id
LEFT JOIN registration_evaluation
ON registration_evaluation.registration_id = last_exam_session.registration_id
WHERE is_individualized = false;

-- name: select-participants-for-onr-check
SELECT p.id AS participant_id, r.person_oid
FROM registration r
INNER JOIN participant p
ON r.participant_id = p.id
LEFT JOIN participant_onr onr
ON onr.oid = r.person_oid
WHERE r.person_oid IS NOT NULL
AND (   (onr.oid IS NULL
         AND current_timestamp > r.created + interval '1 hour')
     OR (onr.oid IS NOT NULL
         AND onr.is_individualized = false
         AND current_timestamp > onr.modified + interval '1 day'
         AND r.created >= date '2024-10-01'));

-- name: upsert-participant-onr-data!
INSERT INTO participant_onr
(oid, participant_id, oppijanumero, is_individualized)
VALUES (:person_oid, :participant_id, :oppijanumero, :is_individualized)
ON CONFLICT (oid) DO UPDATE
SET oppijanumero = :oppijanumero,
    is_individualized = :is_individualized,
    modified = current_timestamp;
