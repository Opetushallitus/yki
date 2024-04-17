-- name: select-participant-onr-data
SELECT * FROM participant_onr;

-- name: select-participants-for-onr-check
SELECT p.id AS participant_id, r.person_oid
FROM registration r
INNER JOIN participant p
ON r.participant_id = p.id
WHERE r.person_oid IS NOT NULL
AND NOT EXISTS
    (SELECT is_individualized FROM participant_onr
     WHERE oid = r.person_oid AND
        is_individualized = true);

-- name: upsert-participant-onr-data!
INSERT INTO participant_onr
(oid, participant_id, oppijanumero, is_individualized)
VALUES (:person_oid, :participant_id, :oppijanumero, :is_individualized)
ON CONFLICT (oid) DO UPDATE
SET oppijanumero = :oppijanumero,
    is_individualized = :is_individualized,
    modified = current_timestamp;
