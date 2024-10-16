-- name: select-organizers
SELECT o.oid, o.agreement_start_date, o.agreement_end_date, o.contact_name, o.contact_email, o.contact_phone_number, o.extra,
  (
    SELECT array_to_json(array_agg(lang))
    FROM (
      SELECT language_code, level_code
      FROM exam_language
      WHERE organizer_id = o.id
    ) lang
  ) AS languages
FROM organizer o
WHERE o.deleted_at IS NULL;

-- name: select-organizers-by-oids
SELECT o.oid, o.agreement_start_date, o.agreement_end_date, o.contact_name, o.contact_email, o.contact_phone_number, o.extra,
  (
    SELECT array_to_json(array_agg(lang))
    FROM (
      SELECT language_code, level_code
      FROM exam_language
      WHERE organizer_id = o.id
    ) lang
  ) AS languages
FROM organizer o
WHERE o.deleted_at IS NULL AND o.oid IN (:oids);

-- name: select-organizer
SELECT
  o.oid,
  o.agreement_start_date,
  o.agreement_end_date,
  o.contact_name,
  o.contact_email,
  o.contact_phone_number,
  o.extra
FROM organizer o
WHERE o.oid = :oid AND o.deleted_at IS NULL;

-- name: insert-organizer!
INSERT INTO organizer (
  oid,
  agreement_start_date,
  agreement_end_date,
  contact_name,
  contact_email,
  contact_phone_number,
  extra
) VALUES (
  :oid,
  :agreement_start_date,
  :agreement_end_date,
  :contact_name,
  :contact_email,
  :contact_phone_number,
  :extra
) ON CONFLICT DO NOTHING;

-- name: update-organizer!
UPDATE organizer
SET
  agreement_start_date = :agreement_start_date,
  agreement_end_date = :agreement_end_date,
  contact_name = :contact_name,
  contact_email = :contact_email,
  contact_phone_number = :contact_phone_number,
  extra = :extra,
  modified = current_timestamp
WHERE oid = :oid AND deleted_at IS NULL;

-- name: delete-organizer!
UPDATE organizer
SET deleted_at = current_timestamp
WHERE oid = :oid AND deleted_at IS NULL;

-- name: delete-organizer-languages!
DELETE FROM exam_language
WHERE organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL);

-- name: insert-organizer-language!
INSERT INTO exam_language (
  language_code,
  level_code,
  organizer_id
) VALUES (
  :language_code,
  :level_code,
  (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL)
);

-- name: delete-quarantine!
UPDATE quarantine SET deleted_at=current_timestamp WHERE id = :id AND deleted_at IS NULL;

-- name: select-quarantines
SELECT
  q.id,
  q.language_code,
  q.start_date,
  q.end_date,
  q.birthdate,
  q.ssn,
  q.first_name,
  q.last_name,
  q.email,
  q.phone_number,
  q.diary_number,
  q.created
FROM quarantine q
WHERE deleted_at IS NULL
ORDER BY q.id DESC;

-- name: select-quarantine
SELECT *
FROM quarantine q
WHERE q.id = :id AND deleted_at IS NULL;

-- name: insert-quarantine<!
INSERT INTO quarantine (
  language_code,
  start_date,
  end_date,
  birthdate,
  ssn,
  first_name,
  last_name,
  email,
  phone_number,
  diary_number
) VALUES (
  :language_code,
  :start_date,
  :end_date,
  :birthdate,
  :ssn,
  :first_name,
  :last_name,
  :email,
  :phone_number,
  :diary_number
);

-- name: update-quarantine<!
UPDATE quarantine
SET language_code = :language_code,
    start_date = :start_date,
    end_date = :end_date,
    birthdate = :birthdate,
    first_name = :first_name,
    last_name = :last_name,
    ssn = :ssn,
    email = :email,
    phone_number = :phone_number,
    diary_number = :diary_number,
    updated = current_timestamp
WHERE id = :id AND deleted_at IS NULL;

-- name: select-quarantine-matches
SELECT
  q.id,
  q.language_code AS quarantine_lang,
  q.birthdate,
  q.created,
  q.ssn,
  q.first_name,
  q.last_name,
  q.email,
  q.phone_number,
  r.id AS registration_id,
  r.form,
  r.state,
  ed.exam_date,
  es.language_code
FROM quarantine q
INNER JOIN registration r
  ON (q.ssn = r.form->>'ssn' OR q.birthdate = r.form->>'birthdate')
INNER JOIN exam_session es
  ON r.exam_session_id = es.id
INNER JOIN exam_date ed
  ON es.exam_date_id = ed.id
WHERE r.state IN ('SUBMITTED', 'COMPLETED')
  AND es.language_code = q.language_code
  -- Filter out possible matches that have been reviewed after quarantine was last updated
  AND NOT EXISTS (SELECT qr.id FROM quarantine_review qr WHERE qr.registration_id = r.id AND qr.quarantine_id = q.id AND q.updated <= qr.updated)
  AND ed.exam_date BETWEEN q.start_date AND q.end_date
  AND q.deleted_at IS NULL
ORDER BY q.id DESC, r.id;

-- name: select-quarantine-reviews
SELECT
  qr.id,
  qr.quarantined AS is_quarantined,
  qr.quarantine_id,
  qr.registration_id,
  qr.updated,
  ed.exam_date,
  es.language_code,
  q.birthdate,
  q.first_name,
  q.last_name,
  q.ssn,
  q.email,
  q.phone_number,
  r.form,
  r.state
FROM quarantine_review qr
INNER JOIN quarantine q
  ON qr.quarantine_id = q.id
INNER JOIN registration r
  ON qr.registration_id = r.id
INNER JOIN exam_session es
  ON r.exam_session_id = es.id
INNER JOIN exam_date ed
  ON es.exam_date_id = ed.id
WHERE q.deleted_at IS NULL
ORDER BY qr.id DESC;

-- name: upsert-quarantine-review<!
INSERT INTO quarantine_review (
  quarantine_id,
  registration_id,
  quarantined,
  reviewer_oid
) VALUES (
  :quarantine_id,
  :registration_id,
  :quarantined,
  :reviewer_oid
)
ON CONFLICT ON CONSTRAINT quarantine_review_unique_quarantine_registration_combination
DO UPDATE SET quarantined = :quarantined, reviewer_oid = :reviewer_oid, updated = current_timestamp;

-- name: cancel-registration!
UPDATE registration SET
    state =
        CASE WHEN state = 'COMPLETED'::registration_state THEN 'PAID_AND_CANCELLED'::registration_state
            ELSE 'CANCELLED'::registration_state
        END
WHERE id = :id AND state NOT IN ('CANCELLED', 'PAID_AND_CANCELLED');

-- name: cancel-registration-to-upcoming-exam!
UPDATE registration r SET
    state =
        CASE WHEN state = 'COMPLETED'::registration_state THEN 'PAID_AND_CANCELLED'::registration_state
             ELSE 'CANCELLED'::registration_state
            END
WHERE r.id = :id
  AND r.state NOT IN ('CANCELLED', 'PAID_AND_CANCELLED')
  AND now() < (
      SELECT ed.exam_date FROM exam_date ed
      INNER JOIN exam_session es ON ed.id = es.exam_date_id
      WHERE es.id = r.exam_session_id);

-- name: insert-exam-session<!
INSERT INTO exam_session (
  organizer_id,
  language_code,
  level_code,
  exam_date_id,
  max_participants,
  office_oid,
  published_at
) VALUES (
  (SELECT id FROM organizer
    WHERE oid = :oid AND deleted_at IS NULL AND agreement_end_date >= :session_date AND agreement_start_date <= :session_date
      AND current_timestamp BETWEEN agreement_start_date AND agreement_end_date),
  (SELECT language_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL AND current_timestamp BETWEEN agreement_start_date AND agreement_end_date)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  (SELECT level_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL AND current_timestamp BETWEEN agreement_start_date AND agreement_end_date)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  (SELECT id from exam_date WHERE exam_date = :session_date AND deleted_at IS NULL),
  :max_participants,
  :office_oid,
  :published_at
);

-- name: insert-exam-session-location!
INSERT INTO exam_session_location(
  name,
  street_address,
  post_office,
  zip,
  other_location_info,
  extra_information,
  lang,
  exam_session_id
) VALUES (
  :name,
  :street_address,
  :post_office,
  :zip,
  :other_location_info,
  :extra_information,
  :lang,
  :exam_session_id
);

-- name: select-exam-session-office-oids
SELECT es.office_oid
FROM exam_session es
INNER JOIN organizer o ON es.organizer_id = o.id
WHERE o.oid = :oid;

-- name: select-exam-sessions
SELECT
  e.id,
  language_code,
  level_code,
  ed.exam_date AS session_date,
  e.max_participants,
  ed.registration_start_date,
  ed.registration_end_date,
  e.post_admission_activated_at,
  e.post_admission_quota,
  e.post_admission_active,
  ed.post_admission_start_date,
  ed.post_admission_end_date,
  e.office_oid,
  e.published_at,
  (SELECT COUNT(1)
   FROM exam_session_queue
   WHERE exam_session_id = e.id) as queue,
  ((SELECT COUNT(1)
    FROM exam_session_queue
    WHERE exam_session_id = e.id) >= 50) as queue_full,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id AND re.kind = 'ADMISSION' AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) as participants,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id AND re.kind = 'POST_ADMISSION' AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED')) as pa_participants,
  o.oid as organizer_oid,
  (SELECT array_to_json(array_agg(loc))
   FROM (SELECT
           name,
           street_address,
           post_office,
           zip,
           other_location_info,
           extra_information,
           lang
         FROM exam_session_location
         WHERE exam_session_id = e.id) loc
  ) as location,
  (SELECT post_admission_enabled FROM exam_date WHERE id = e.exam_date_id) AS post_admission_enabled,
  (within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
      OR (within_dt_range(now(), ed.post_admission_start_date, ed.post_admission_end_date) AND e.post_admission_active = TRUE AND ed.post_admission_enabled = TRUE)) as open,
  (now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00')) AS upcoming_admission,
  (e.post_admission_active = TRUE AND ed.post_admission_enabled = TRUE AND (now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.post_admission_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00'))) AS upcoming_post_admission
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE ed.exam_date >= :from
ORDER BY ed.exam_date ASC;

-- name: select-exam-sessions-for-oid
SELECT
  e.id,
  language_code,
  level_code,
  ed.exam_date AS session_date,
  e.max_participants,
  ed.registration_start_date,
  ed.registration_end_date,
  e.post_admission_activated_at,
  e.post_admission_quota,
  e.post_admission_active,
  ed.post_admission_start_date,
  ed.post_admission_end_date,
  e.office_oid,
  e.published_at,
  (SELECT COUNT(1)
    FROM exam_session_queue
    WHERE exam_session_id = e.id) as queue,
  ((SELECT COUNT(1)
    FROM exam_session_queue
    WHERE exam_session_id = e.id) >= 50) as queue_full,
  (SELECT COUNT(1)
    FROM registration re
    WHERE re.exam_session_id = e.id AND re.kind = 'ADMISSION' AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) as participants,
  (SELECT COUNT(1)
    FROM registration re
    WHERE re.exam_session_id = e.id AND re.kind = 'POST_ADMISSION' AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED')) as pa_participants,
  o.oid as organizer_oid,
 (
  SELECT array_to_json(array_agg(contact_row))
  FROM (
    SELECT
        name,
        email,
        phone_number
        FROM contact co
        WHERE co.id = (SELECT esc.contact_id FROM exam_session_contact esc WHERE esc.exam_session_id = e.id AND deleted_at IS NULL LIMIT 1)
        AND deleted_at IS NULL
    ) contact_row
 ) as contact,
 (
  SELECT array_to_json(array_agg(loc))
  FROM (
    SELECT
      name,
      street_address,
      post_office,
      zip,
      other_location_info,
      extra_information,
      lang
    FROM exam_session_location
    WHERE exam_session_id = e.id
  ) loc
 ) as location,
(SELECT post_admission_enabled FROM exam_date WHERE id = e.exam_date_id) AS post_admission_enabled,
  (within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
  OR (within_dt_range(now(), ed.post_admission_start_date, ed.post_admission_end_date) AND e.post_admission_active = TRUE AND ed.post_admission_enabled = TRUE)) as open,
(now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00')) AS upcoming_admission,
(e.post_admission_active = TRUE AND ed.post_admission_enabled = TRUE AND (now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.post_admission_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00'))) AS upcoming_post_admission
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE ed.exam_date >= COALESCE(:from, ed.exam_date)
  AND o.oid = :oid
ORDER BY ed.exam_date ASC;

-- name: select-exam-session-by-id
SELECT
  e.id,
  ed.exam_date AS session_date,
  ed.registration_start_date,
  ed.registration_end_date,
  e.post_admission_activated_at,
  ed.post_admission_start_date,
  ed.post_admission_end_date,
  ed.post_admission_enabled,
  e.post_admission_quota,
  e.language_code,
  e.level_code,
  e.max_participants,
  e.office_oid,
  e.post_admission_active,
  e.published_at,
(SELECT COUNT(1)
  FROM exam_session_queue
  WHERE exam_session_id = e.id) as queue,
((SELECT COUNT(1)
  FROM exam_session_queue
  WHERE exam_session_id = e.id) >= 50) as queue_full,
(SELECT COUNT(1)
  FROM registration re
  WHERE re.exam_session_id = e.id AND re.kind = 'ADMISSION' AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) AS participants,
(SELECT COUNT(1)
  FROM registration re
  WHERE re.exam_session_id = e.id AND re.kind = 'POST_ADMISSION' AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED')) as pa_participants,
o.oid as organizer_oid,
(
  SELECT array_to_json(array_agg(contact_row))
  FROM (
    SELECT
        name,
        email,
        phone_number
        FROM contact co
        WHERE co.id = (SELECT esc.contact_id FROM exam_session_contact esc WHERE esc.exam_session_id = e.id AND deleted_at IS NULL LIMIT 1)
        AND deleted_at IS NULL
    ) contact_row
 ) as contact,
(SELECT array_to_json(array_agg(loc))
  FROM (
    SELECT
      name,
      street_address,
      post_office,
      zip,
      other_location_info,
      extra_information,
      lang
    FROM exam_session_location
    WHERE exam_session_id = e.id
  ) loc
) AS location,
(within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
  OR (within_dt_range(now(), ed.post_admission_start_date, ed.post_admission_end_date) AND e.post_admission_active AND ed.post_admission_enabled)) as open,
(now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00')) AS upcoming_admission,
(e.post_admission_active = TRUE AND ed.post_admission_enabled = TRUE AND (now() AT TIME ZONE 'Europe/Helsinki' < (date_trunc('day', ed.post_admission_end_date AT TIME ZONE 'Europe/Helsinki') + time '16:00'))) AS upcoming_post_admission
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE e.id = :id;

-- name: select-exam-session-registration-by-registration-id
SELECT
  es.id,
  es.language_code,
  es.level_code,
  es.max_participants,
  es.office_oid,
  es.published_at,
  re.state
FROM exam_session es
INNER JOIN registration re ON es.id = re.exam_session_id
WHERE re.id = :registration_id;

-- name: select-exam-session-with-location
SELECT
  es.language_code,
  es.level_code,
  ed.exam_date,
  ed.registration_end_date,
  esl.street_address,
  esl.post_office,
  esl.zip,
  esl.name,
  (within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
  OR (es.post_admission_active = TRUE
    AND ed.post_admission_enabled = TRUE
    AND within_dt_range(now(), ed.post_admission_start_date, ed.post_admission_end_date))) as open
FROM exam_session es
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE es.id = :id
  AND esl.lang = :lang;

-- name: update-exam-session!
UPDATE exam_session
SET
  exam_date_id = (SELECT id FROM exam_date WHERE exam_date = :session_date),
  language_code =
  (SELECT language_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer
                              WHERE oid = :oid
                                AND deleted_at IS NULL AND agreement_end_date >= :session_date
                                AND agreement_start_date <= :session_date)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  level_code =
  (SELECT level_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer
                              WHERE oid = :oid
                                AND deleted_at IS NULL AND agreement_end_date >= :session_date
                                AND agreement_start_date <= :session_date)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  max_participants = :max_participants,
  office_oid = :office_oid,
  published_at = :published_at,
  modified = current_timestamp
WHERE id = :id;

-- name: delete-exam-session-location!
DELETE FROM exam_session_location
WHERE exam_session_id = :id;

-- name: delete-exam-session!
DELETE FROM exam_session
WHERE id = (SELECT es.id FROM exam_session es
            INNER JOIN exam_date ed ON ed.id = es.exam_date_id
            WHERE es.id = :id AND ed.registration_start_date >= current_date
            AND es.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid));

-- name: insert-participant<!
INSERT INTO participant(
  external_user_id,
  email
) VALUES (
  :external_user_id,
  :email
);

-- name: update-participant-email!
UPDATE participant
SET email = :email
WHERE id = :id;

-- name: insert-login-link<!
INSERT INTO login_link(
  code,
  type,
  participant_id,
  exam_session_id,
  registration_id,
  expired_link_redirect,
  success_redirect,
  expires_at
) VALUES (
  :code,
  :type::login_link_type,
  :participant_id,
  :exam_session_id,
  :registration_id,
  :expired_link_redirect,
  :success_redirect,
  :expires_at
);

-- name: select-recent-login-link-by-exam-session-and-participant-id
SELECT l.id, l.created
FROM login_link l
WHERE l.exam_session_id = :exam_session_id
AND   l.participant_id = :participant_id
AND   l.created > :older_than;

-- name: select-login-link-by-code
SELECT
 l.code,
 p.external_user_id,
 p.email,
 l.exam_session_id,
 l.expires_at,
 l.expired_link_redirect,
 l.success_redirect
FROM login_link l INNER JOIN participant p
  ON l.participant_id = p.id
WHERE l.code = :code;

-- name: select-login-link-by-exam-session-and-registration-id
SELECT
  l.code,
  l.participant_id,
  l.type
FROM login_link l
WHERE l.registration_id = :registration_id;

-- name: try-to-acquire-lock!
UPDATE task_lock SET
  last_executed = current_timestamp,
  worker_id = :worker_id
WHERE task = :task
  AND last_executed < (current_timestamp - :interval::interval);

-- name: insert-registration<!
INSERT INTO registration(
  state,
  exam_session_id,
  participant_id,
  started_at,
  kind
) SELECT
  'STARTED',
  :exam_session_id,
  :participant_id,
  :started_at,
  select_registration_phase(:exam_session_id)::registration_kind
  -- only one registration per participant on same exam date
  WHERE NOT EXISTS (SELECT es.id
                    FROM exam_session es
                    INNER JOIN registration re ON es.id = re.exam_session_id
                    WHERE re.participant_id = :participant_id
                      AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
                      AND es.exam_date_id =
                        (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id));


-- name: update-registration-to-submitted!
UPDATE registration SET
  state = 'SUBMITTED',
  modified = current_timestamp,
  form = :form,
  person_oid = :oid,
  form_version = :form_version
WHERE
  id = :id
  AND state = 'STARTED'
  AND participant_id = :participant_id;

-- name: cancel-registration-for-participant!
UPDATE registration SET
  state = 'CANCELLED',
  modified = current_timestamp
WHERE
  id = :id AND
  participant_id = :participant_id AND
  state = 'STARTED';

-- name: select-exam-session-registration-open
SELECT exam_session_registration_open(:exam_session_id) as exists;

-- name: select-exam-session-post-registration-open
SELECT exam_session_post_registration_open(:exam_session_id) as exists;

-- name: select-exam-session-space-left
SELECT NOT EXISTS (
	SELECT es.max_participants
	FROM exam_session es
	LEFT JOIN registration re ON es.id = re.exam_session_id
	WHERE re.exam_session_id = :exam_session_id
    AND re.id != COALESCE(:registration_id, 0)
	  AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
      AND re.kind = 'ADMISSION'
	GROUP BY es.max_participants
	HAVING (es.max_participants - COUNT(re.id)) <= 0
) as exists;

-- name: select-exam-session-quota-left
SELECT NOT EXISTS (
    SELECT es.post_admission_quota
      FROM exam_session es
 LEFT JOIN registration re ON es.id = re.exam_session_id
     WHERE re.exam_session_id = :exam_session_id
       AND re.id != COALESCE(:registration_id, 0)
       AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
       AND re.kind = 'POST_ADMISSION'
  GROUP BY es.post_admission_quota
    HAVING (es.post_admission_quota - COUNT(re.id)) <= 0
) as exists;

-- name: select-not-registered-to-exam-session
SELECT NOT EXISTS (
  SELECT es.id
  FROM exam_session es
  INNER JOIN registration re ON es.id = re.exam_session_id
  WHERE re.participant_id = :participant_id
    AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
    AND es.exam_date_id = (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id)
) as exists;

-- name: select-started-registration-id-by-participant
SELECT re.id
FROM exam_session es
INNER JOIN registration re ON es.id = re.exam_session_id
WHERE re.participant_id = :participant_id
  AND re.state = 'STARTED'
  AND es.id = :exam_session_id;

-- name: select-registration
SELECT state, exam_session_id, participant_id, es.organizer_id, ed.exam_date
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
WHERE re.id = :id
  AND re.state = 'SUBMITTED'
  AND p.external_user_id = :external_user_id;

-- name: select-started-registrations-to-expire
SELECT id FROM registration
WHERE state = 'STARTED' AND (started_at + interval '30 minutes') < current_timestamp;

-- submitted registration expires 3 days from creation at midnight
-- name: select-submitted-registrations-to-expire
SELECT id FROM registration
WHERE state = 'SUBMITTED'
  AND ((kind = 'ADMISSION' AND ts_older_than(created, interval '4 days'))
    OR (kind = 'POST_ADMISSION' AND ts_older_than(created, interval '2 days')));

-- name: expire-registrations-by-ids!
UPDATE registration
SET state = 'EXPIRED',
    modified = current_timestamp
WHERE id IN (:ids) AND state IN ('STARTED', 'SUBMITTED');

-- name: update-registration-exam-session!
UPDATE registration
SET exam_session_id = :exam_session_id,
    kind = 'ADMISSION',
    original_exam_session_id = exam_session_id
WHERE id = :registration_id
AND EXISTS (SELECT id
            FROM exam_session
            WHERE id = :exam_session_id
              AND organizer_id IN (SELECT id FROM organizer WHERE oid = :oid));

-- name: select-registration-data
SELECT re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       ed.post_admission_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND ((re.kind = 'ADMISSION' AND (ed.registration_end_date + time '16:00' AT TIME ZONE 'Europe/Helsinki') >= (current_timestamp AT TIME ZONE 'Europe/Helsinki'))
       OR (re.kind = 'POST_ADMISSION' AND (ed.post_admission_end_date + time '16:00' AT TIME ZONE 'Europe/Helsinki') >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')))
  AND (re.state = 'STARTED' OR re.state = 'SUBMITTED')
  AND esl.lang = :lang
  AND re.participant_id = :participant_id;

-- name: select-registration-and-exam-session-state
SELECT re.state, exam_session_registration_open(es.id) AS open, exam_session_post_registration_open(es.id) AS post_admission_open
FROM registration re
INNER JOIN exam_session es on re.exam_session_id = es.id
WHERE re.id = :id;

-- name: select-registration-data-by-participant
SELECT re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       ed.post_admission_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND ((re.kind = 'ADMISSION' AND (ed.registration_end_date + time '16:00' AT TIME ZONE 'Europe/Helsinki') >= (current_timestamp AT TIME ZONE 'Europe/Helsinki'))
    OR (re.kind = 'POST_ADMISSION' AND (ed.post_admission_end_date + time '16:00' AT TIME ZONE 'Europe/Helsinki') >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')))
  AND (re.state = 'STARTED' OR re.state = 'SUBMITTED')
  AND esl.lang = :lang
  AND EXISTS (SELECT 1
       FROM registration as reg
       WHERE reg.participant_id = :participant_id
         AND reg.state = 'STARTED'
         AND reg.exam_session_id = es.id);

-- name: select-completed-registration-details
SELECT re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       re.form->>'email' AS email,
       re.form->>'last_name' AS last_name,
       re.form->>'first_name' AS first_name,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       ed.post_admission_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND re.exam_session_id = :exam_session_id
  AND re.state = 'COMPLETED'
  AND esl.lang = :lang;

-- name: select-open-registrations-by-participant
SELECT re.exam_session_id, (started_at + interval '30 minutes') AS expires_at
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
WHERE p.external_user_id = :external_user_id
  AND re.state = 'STARTED';

-- name: select-registration-details-for-new-payment
SELECT re.id,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       re.form,
       re.state,
       p.email,
       p.external_user_id,
       esl.name,
       es.language_code,
       es.level_code,
       es.organizer_id,
       ed.exam_date
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND p.external_user_id = :external_user_id;

-- name: select-new-exam-payment-details
SELECT p.id,
       p.amount,
       p.registration_id,
       p.reference,
       p.state,
       r.exam_session_id
FROM exam_payment_new p
INNER JOIN registration r ON r.id = p.registration_id
WHERE p.transaction_id = :transaction_id;

--name: select-completed-payment-details-for-registration
SELECT p.id,
       p.amount,
       p.reference,
       p.transaction_id,
       p.paid_at
FROM exam_payment_new p
WHERE p.state = 'PAID'
AND p.registration_id = :registration_id;

-- name: insert-new-exam-payment<!
INSERT INTO exam_payment_new(
  state,
  registration_id,
  amount,
  reference,
  transaction_id,
  href) VALUES (
  'UNPAID',
  :registration_id,
  :amount,
  :reference,
  :transaction_id,
  :href);

-- name: update-new-exam-payment-to-paid<!
UPDATE exam_payment_new
SET state = 'PAID',
    paid_at = current_timestamp,
    modified = current_timestamp
WHERE id = :id AND state != 'PAID';

-- name: update-new-exam-payment-to-cancelled!
UPDATE exam_payment_new
SET state = 'ERROR',
    modified = current_timestamp
WHERE id = :id AND state = 'UNPAID';

-- name: update-new-evaluation-payment-to-paid<!
UPDATE evaluation_payment_new
SET state = 'PAID',
    paid_at = current_timestamp,
    modified = current_timestamp
WHERE id = :id AND state != 'PAID';

-- name: complete-registration<!
UPDATE registration
SET state =
    CASE WHEN state = 'SUBMITTED'::registration_state THEN 'COMPLETED'::registration_state
         ELSE 'PAID_AND_CANCELLED'::registration_state
    END,
    modified = current_timestamp
WHERE id = :id AND state IN ('SUBMITTED', 'EXPIRED', 'CANCELLED');

-- name: select-participant-by-external-id
SELECT id, external_user_id, email
FROM participant
WHERE external_user_id = :external_user_id;

-- name: select-participant-by-id
SELECT id, external_user_id, email
FROM participant
WHERE id = :id;

-- name: select-participant-data-by-registration-id
SELECT p.email,
       es.language_code,
       es.level_code,
       esl.name,
       esl.street_address,
       esl.zip,
       esl.post_office,
       ed.exam_date,
       re.form->>'last_name' AS last_name,
       re.form->>'first_name' AS first_name
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
WHERE re.id = :id;

-- name: insert-virkailija-ticket!
INSERT INTO cas_ticketstore (ticket) VALUES (:ticket);

-- name: delete-virkailija-ticket!
DELETE FROM cas_ticketstore
WHERE ticket = :ticket;

-- name: select-virkailija-ticket
SELECT ticket
FROM cas_ticketstore
WHERE ticket = :ticket;

-- name: insert-oppija-ticket!
INSERT INTO cas_oppija_ticketstore (ticket) VALUES (:ticket);

-- name: delete-oppija-ticket!
DELETE FROM cas_oppija_ticketstore
WHERE ticket = :ticket;

-- name: select-oppija-ticket
SELECT ticket
FROM cas_oppija_ticketstore
WHERE ticket = :ticket;

--name: insert-participants-sync-status!
INSERT INTO participant_sync_status(
  exam_session_id
) SELECT
  :exam_session_id
  WHERE NOT EXISTS (SELECT exam_session_id
                    FROM participant_sync_status
                    WHERE exam_session_id = :exam_session_id)
ON CONFLICT DO NOTHING;

--name: select-relocated-session-for-sync
SELECT es.id
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE es.id = :exam_session_id
  AND (ed.exam_date - interval '21 day') >= current_date
  AND (SELECT COUNT(1)
     FROM participant_sync_status pss
     WHERE pss.exam_session_id = es.id
            AND pss.success_at IS NULL)  = 0;

--name: insert-relocated-participants-sync-status!
INSERT INTO participant_sync_status(
  relocated_at,
  exam_session_id
) VALUES (
  current_timestamp,
  :exam_session_id)
ON CONFLICT DO NOTHING;

-- Syncronization is done during registration period and
-- failed sync attempts will be retried for given period
-- after registration has ended.
-- Exam sessions where participants have been relocated to another
-- session after the registration has ended, are synced and retried
-- for one day after the relocation.

-- name: select-exam-sessions-to-be-synced
SELECT es.id as exam_session_id, pss.created
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN participant_sync_status pss ON pss.exam_session_id = es.id
WHERE ((((ed.registration_end_date + interval '1 day') >= current_date
    OR (ed.post_admission_end_date + interval '1 day') >= current_date
    OR ((ed.registration_end_date + :duration::interval) >= current_date
        AND pss.failed_at IS NOT NULL
        AND (pss.success_at IS NULL OR pss.failed_at > pss.success_at)))
    AND (ed.registration_start_date <= current_date OR es.post_admission_start_date <= current_date))
  OR (pss.relocated_at IS NOT NULL
    AND pss.success_at IS NULL
    AND ed.registration_start_date < current_date
    AND (pss.relocated_at + interval '1 day') > current_date))
AND (SELECT COUNT(1)
     FROM registration re
     WHERE re.exam_session_id = es.id AND re.state = 'COMPLETED') > 0;

-- name: update-participant-sync-to-success!
UPDATE participant_sync_status
SET success_at = current_timestamp
WHERE exam_session_id = :exam_session_id;

-- name: update-participant-sync-to-failed!
UPDATE participant_sync_status
SET failed_at = current_timestamp
WHERE exam_session_id = :exam_session_id;

-- name: delete-participant-sync-status!
DELETE FROM participant_sync_status
WHERE exam_session_id = :exam_session_id;

-- name: select-completed-exam-session-participants
SELECT form, person_oid
FROM registration
WHERE exam_session_id = :id
AND state = 'COMPLETED';

-- name: select-exam-session-participants
SELECT
  r.created,
  r.form,
  r.state,
  r.id as registration_id,
  r.kind,
  r.original_exam_session_id,
  oed.exam_date AS original_exam_date
FROM exam_session es
INNER JOIN registration r ON es.id = r.exam_session_id
LEFT JOIN exam_session oes ON oes.id = r.original_exam_session_id
LEFT JOIN exam_date oed ON oed.id = oes.exam_date_id
WHERE es.id = :id
AND es.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid)
AND r.state != 'STARTED'
AND r.form IS NOT NULL
ORDER BY r.created ASC;

--name: cancel-unpaid-registration-for-organizer!
UPDATE registration
SET state = 'CANCELLED'
WHERE id = :id
  AND state NOT IN ('COMPLETED', 'PAID_AND_CANCELLED')
  AND exam_session_id IN (SELECT id
                          FROM exam_session
                          WHERE organizer_id IN
                                (SELECT id
                                 FROM organizer
                                 WHERE oid = :oid));

-- name: select-exam-dates
SELECT ed.id, ed.exam_date, ed.registration_start_date, ed.registration_end_date, ed.post_admission_end_date,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code, level_code
    FROM exam_date_language
    WHERE exam_date_id = ed.id AND deleted_at IS NULL
  ) lang
) AS languages
FROM exam_date ed
WHERE ed.registration_end_date >= current_date AND deleted_at IS NULL
ORDER BY ed.exam_date ASC;

-- name: select-organizer-exam-dates
SELECT
ed.id,
ed.exam_date,
ed.registration_start_date,
ed.registration_end_date,
ed.post_admission_start_date,
ed.post_admission_end_date,
ed.post_admission_enabled,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code, level_code
    FROM exam_date_language
    WHERE exam_date_id = ed.id AND deleted_at IS NULL
  ) lang
) AS languages,
( SELECT COUNT(1)
  FROM exam_session
  WHERE exam_date_id = ed.id) AS exam_session_count,
(SELECT ev.evaluation_start_date FROM evaluation ev WHERE ev.exam_date_id = ed.id LIMIT 1),
(SELECT ev.evaluation_end_date FROM evaluation ev WHERE ev.exam_date_id = ed.id LIMIT 1)
FROM exam_date ed
WHERE ed.exam_date >= COALESCE(:from, current_date) AND ed.deleted_at IS NULL
ORDER BY ed.exam_date ASC;

-- name: insert-exam-date<!
INSERT INTO exam_date (
  exam_date,
  registration_start_date,
  registration_end_date
) VALUES (
  :exam_date,
  :registration_start_date,
  :registration_end_date
);

-- name: insert-exam-date-language!
INSERT INTO exam_date_language(
  exam_date_id,
  language_code,
  level_code
) VALUES (
  :exam_date_id,
  :language_code,
  :level_code
);

-- name: select-exam-date-by-id
SELECT
  ed.id,
  ed.exam_date,
  ed.registration_start_date,
  ed.registration_end_date,
  ed.post_admission_enabled,
  ed.post_admission_start_date,
  ed.post_admission_end_date,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code, level_code
    FROM exam_date_language
    WHERE exam_date_id = ed.id AND deleted_at IS NULL
  ) lang
) AS languages,
( SELECT COUNT(1)
  FROM exam_session
  WHERE exam_date_id = ed.id) AS exam_session_count,
  ev.evaluation_start_date,
ev.evaluation_end_date
FROM exam_date ed
LEFT JOIN evaluation ev ON ev.exam_date_id = ed.id
WHERE ed.id = :id AND ed.deleted_at IS NULL;


-- name: select-exam-dates-by-date
SELECT
  ed.id,
  ed.exam_date,
  ed.registration_start_date,
  ed.registration_end_date,
  ed.post_admission_enabled,
  ed.post_admission_start_date,
  ed.post_admission_end_date,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code, level_code
    FROM exam_date_language
    WHERE exam_date_id = ed.id AND deleted_at IS NULL
  ) lang
) AS languages
FROM exam_date ed
WHERE ed.exam_date = :exam_date AND deleted_at IS NULL;

-- name: select-exam-date-session-count
SELECT
  COUNT(1)
FROM exam_session
WHERE exam_date_id = :id;

-- name: select-exam-date-languages
SELECT
  edl.id,
  edl.exam_date_id,
  edl.language_code,
  edl.level_code
FROM exam_date_language edl
WHERE edl.exam_date_id = :exam_date_id AND edl.deleted_at IS NULL;

-- name: update-exam-date!
UPDATE exam_date
  SET
    exam_date = :exam_date,
    registration_start_date = :registration_start_date,
    registration_end_date = :registration_end_date,
    post_admission_start_date = :post_admission_start_date,
    post_admission_end_date = :post_admission_end_date,
    post_admission_enabled = :post_admission_enabled
  WHERE id = :id;

-- name: delete-exam-date!
UPDATE exam_date
  SET deleted_at = current_timestamp
  WHERE id = :id AND deleted_at IS NULL;

-- name: delete-exam-date-languages!
UPDATE exam_date_language
  SET deleted_at = current_timestamp
  WHERE exam_date_id = :exam_date_id AND deleted_at IS NULL;

-- name: delete-exam-date-language!
UPDATE exam_date_language
  SET deleted_at = current_timestamp
  WHERE exam_date_id = :exam_date_id
    AND level_code = :level_code
    AND language_code = :language_code
    AND deleted_at IS NULL;

-- name: select-exam-session-queue-count
SELECT count(1)
FROM exam_session_queue
WHERE exam_session_id = :exam_session_id;

-- name: insert-exam-session-queue!
INSERT INTO exam_session_queue (
  email,
  lang,
  exam_session_id
) VALUES (
  :email,
  :lang,
  :exam_session_id
);

-- send notification only once per day between 8 - 21 until registration ends
-- name: select-exam-sessions-with-queue
SELECT
 esq.exam_session_id,
 esq.last_notified_at,
 es.language_code,
 es.level_code,
 ed.exam_date,
 ed.registration_start_date,
 esl.name,
 esl.street_address,
 esl.post_office,
 esl.zip,
 array_to_json(array_agg(json_build_object('email', esq.email)::jsonb ||
                         json_build_object('lang', esq.lang)::jsonb ||
                         json_build_object('created', esq.created)::jsonb)) as queue
FROM exam_session_queue esq
INNER JOIN exam_session es ON es.id = esq.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id AND esl.lang = esq.lang
WHERE current_timestamp AT TIME ZONE 'Europe/Helsinki' BETWEEN (current_date + time '08:00' AT TIME ZONE 'Europe/Helsinki') AND (current_date + time '20:59' AT TIME ZONE 'Europe/Helsinki')
  AND ed.registration_start_date <= current_date
  AND (ed.registration_end_date + time '16:00' AT TIME ZONE 'Europe/Helsinki') >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')
  AND (last_notified_at IS NULL OR last_notified_at::date < current_date)
  AND es.max_participants > (SELECT COUNT(1)
                            FROM registration re
                            WHERE re.exam_session_id = es.id AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED'))
GROUP BY esq.exam_session_id, esq.last_notified_at, es.language_code, es.level_code, ed.exam_date, ed.registration_start_date, esl.street_address, esl.post_office, esl.zip, esl.name;

-- name: delete-from-exam-session-queue!
DELETE FROM exam_session_queue
WHERE email = :email
AND exam_session_id IN (SELECT id
                        FROM exam_session
                        WHERE exam_date_id = (SELECT exam_date_id
                                              FROM exam_session
                                              WHERE id = :exam_session_id));

-- name: delete-from-exam-session-queue-by-session-id!
DELETE FROM exam_session_queue
 WHERE id = :exam_session_id;

-- name: update-exam-session-queue-last-notified-at!
UPDATE exam_session_queue
SET last_notified_at = current_timestamp
WHERE exam_session_id = :exam_session_id
  AND email = :email;

--name: select-email-added-to-queue
SELECT COUNT(1)
FROM exam_session_queue
WHERE exam_session_id = :exam_session_id
  AND LOWER(email) = LOWER(:email);

--name: fetch-post-admission-details
SELECT post_admission_activated_at, post_admission_active, post_admission_quota
  FROM exam_session
 WHERE id = :exam_session_id;

--name: activate-post-admission!
UPDATE exam_session
   SET post_admission_active = :post_admission_active
 WHERE id = :exam_session_id;

-- name: activate-exam-session-post-admission!
UPDATE exam_session
   SET post_admission_activated_at = now(),
       post_admission_quota = :post_admission_quota,
       post_admission_active = TRUE
   WHERE id = :exam_session_id;

-- name: deactivate-exam-session-post-admission!
UPDATE exam_session
   SET post_admission_active = FALSE
   WHERE id = :exam_session_id;

--name: update-post-admission-end-date!
UPDATE exam_date
   SET post_admission_end_date = :post_admission_end_date
 WHERE id = :exam_date_id;

--name: delete-post-admission-end-date!
UPDATE exam_date
   SET post_admission_end_date = NULL
 WHERE id = :exam_date_id;

--name: select-contacts-by-oid
SELECT
  con.id,
  con.organizer_id,
  con.name,
  con.email,
  con.phone_number,
  con.created,
  con.modified,
  :oid as organizer_oid
FROM contact con
WHERE con.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid)
  AND con.deleted_at IS NULL;

--name: insert-contact<!
INSERT INTO contact (
  organizer_id,
  name,
  email,
  phone_number
) VALUES (
    (SELECT id FROM organizer
      WHERE oid = :oid AND deleted_at IS NULL),
    :name,
    :email,
    :phone_number
);

--name: insert-exam-session-contact<!
INSERT INTO exam_session_contact (
  exam_session_id,
  contact_id
) VALUES (
  :exam_session_id,
  :contact_id
);

-- name: select-exam-session-contact-id
SELECT esc.id
  FROM exam_session_contact esc
WHERE esc.exam_session_id = :exam_session_id
  AND esc.contact_id = :contact_id
  AND deleted_at IS NULL;

--name: select-contact-id-with-details
SELECT
  con.id
FROM contact con
WHERE con.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid)
  AND con.name = :name
  AND con.email = :email
  AND con.phone_number = :phone_number
  AND con.deleted_at IS NULL;

--name: select-existing-session-contact
SELECT esc.id
  FROM exam_session_contact esc
WHERE esc.exam_session_id = :exam_session_id
  AND esc.contact_id = (SELECT
      con.id
    FROM contact con
    WHERE con.name = :name
      AND con.email = :email
      AND con.phone_number = :phone_number
      AND con.deleted_at IS NULL)
  AND deleted_at IS NULL;

--name: delete-exam-session-contact-by-session-id!
DELETE FROM exam_session_contact
  WHERE exam_session_id = :exam_session_id;

--name: select-exam-session-contact-info
SELECT
  co.name,
  co.email,
  co.phone_number
FROM contact co
INNER JOIN exam_session_contact esc on co.id = esc.contact_id
WHERE co.deleted_at IS NULL
AND esc.deleted_at IS NULL
AND esc.exam_session_id = :id;

--name: select-exam-session-extra-information
SELECT
  esl.extra_information
FROM exam_session_location esl
WHERE esl.exam_session_id = :id
AND esl.lang = :lang;

--name: select-evaluation-by-id
SELECT
  ep.id,
  ed.exam_date,
  edl.language_code,
  edl.level_code,
  ep.evaluation_start_date,
  ep.evaluation_end_date,
  (ep.evaluation_start_date <= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE)
    AND  (ep.evaluation_end_date >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE) as open
FROM evaluation ep
INNER JOIN exam_date_language edl on ep.exam_date_language_id = edl.id
INNER JOIN exam_date ed ON edl.exam_date_id = ed.id
WHERE ep.deleted_at IS NULL
  AND  ep.id = :evaluation_id;

--name: select-upcoming-evaluation-periods
SELECT
  ep.id,
  ed.exam_date,
  edl.language_code,
  edl.level_code,
  ep.evaluation_start_date,
  ep.evaluation_end_date,
  (ep.evaluation_start_date <= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE)
    AND  (ep.evaluation_end_date >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE) as open
FROM evaluation ep
INNER JOIN exam_date_language edl on ep.exam_date_language_id = edl.id
INNER JOIN exam_date ed ON edl.exam_date_id = ed.id
WHERE ep.deleted_at IS NULL
  AND  (ep.evaluation_end_date >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE);

--name: insert-evaluation!
INSERT INTO evaluation (
  exam_date_id,
  exam_date_language_id,
  evaluation_start_date,
  evaluation_end_date
) VALUES (
  :exam_date_id,
  :exam_date_language_id,
  :evaluation_start_date,
  :evaluation_end_date
);
--name: select-evaluations-by-exam-date-id
SELECT ev.id,
ev.exam_date_language_id,
ev.evaluation_start_date,
ev.evaluation_end_date
FROM evaluation ev
WHERE ev.exam_date_id = :exam_date_id AND ev.deleted_at IS NULL;

--name: insert-evaluation-order<!
INSERT INTO evaluation_order (
  evaluation_id,
  first_names,
  last_name,
  email,
  birthdate
) VALUES (
  :evaluation_id,
  :first_names,
  :last_name,
  :email,
  :birthdate
);

--name: insert-evaluation-order-subtest!
INSERT INTO evaluation_order_subtest (
  evaluation_order_id,
  subtest
) VALUES (
  :evaluation_order_id,
  :subtest
);

-- name: select-next-evaluation-order-number-suffix
SELECT nextval('payment_order_number_seq');

--name: insert-initial-evaluation-payment-new<!
INSERT INTO evaluation_payment_new(
  state,
  evaluation_order_id,
  amount,
  reference,
  transaction_id,
  href
) VALUES (
  'UNPAID',
  :evaluation_order_id,
  :amount,
  :reference,
  :transaction_id,
  :href
);

--name: select-evaluation-order-by-id
SELECT
  eo.id,
  edl.language_code,
  edl.level_code,
  ed.exam_date,
  (
    SELECT array_to_json(array_agg(subtest))
    FROM (
      SELECT subtest
      FROM evaluation_order_subtest
      WHERE evaluation_order_id= eo.id
    ) subtest
  ) AS subtests
FROM evaluation_order eo
INNER JOIN evaluation ev ON eo.evaluation_id = ev.id
INNER JOIN exam_date_language edl on ev.exam_date_language_id = edl.id
INNER JOIN exam_date ed ON edl.exam_date_id = ed.id
WHERE eo.id = :evaluation_order_id;

--name: select-new-evaluation-payment-by-order-id
SELECT
  epn.amount,
  epn.state,
  epn.href
FROM evaluation_payment_new epn
WHERE epn.evaluation_order_id = :evaluation_order_id;

-- name: select-evaluation-payment-new-by-transaction-id
SELECT
  epn.id,
  epn.amount,
  epn.state,
  epn.evaluation_order_id,
  epn.reference
FROM evaluation_payment_new epn
WHERE epn.transaction_id = :transaction_id;

-- name: select-evaluation-order-with-subtests-by-order-id
SELECT
  eo.first_names,
  eo.last_name,
  eo.email,
  eo.birthdate,
  eo.created,
  edl.language_code,
  edl.level_code,
  ed.exam_date,
  (
      SELECT array_to_json(array_agg(subtest))
      FROM (
               SELECT subtest
               FROM evaluation_order_subtest
               WHERE evaluation_order_id= eo.id
           ) subtest
  ) AS subtests
FROM evaluation_order eo
INNER JOIN evaluation ev ON eo.evaluation_id = ev.id
INNER JOIN exam_date_language edl on ev.exam_date_language_id = edl.id
INNER JOIN exam_date ed on edl.exam_date_id = ed.id
WHERE eo.id = :evaluation_order_id;

-- name: select-completed-new-exam-payments-for-timerange
SELECT
  epn.reference,
  epn.amount,
  epn.paid_at,
  r.form,
  es.language_code,
  es.level_code,
  ed.exam_date,
  o.oid,
  oed.exam_date AS original_exam_date
FROM exam_payment_new epn
INNER JOIN registration r ON epn.registration_id = r.id
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
INNER JOIN organizer o on es.organizer_id = o.id
LEFT JOIN exam_session oes ON r.original_exam_session_id = oes.id
LEFT JOIN exam_date oed ON oes.exam_date_id = oed.id
WHERE (date_trunc('day', :from_inclusive) AT TIME ZONE 'Europe/Helsinki')::DATE <= epn.paid_at AND
      epn.paid_at < (date_trunc('day', :to_exclusive) AT TIME ZONE 'Europe/Helsinki')::DATE;

-- name: select-unpaid-new-exam-payments-by-registration-id
SELECT epn.href
FROM exam_payment_new epn
WHERE epn.registration_id = :registration_id AND epn.state = 'UNPAID';

-- name: delete-exam-session-queue-entries-for-old-exam-dates!
DELETE FROM exam_session_queue
WHERE exam_session_id IN
      (SELECT es.id
       FROM exam_session es
       INNER JOIN exam_date ed on es.exam_date_id = ed.id
       WHERE ed.exam_date + interval '1 month' < current_date);

-- name: delete-old-cas-tickets!
DELETE FROM cas_ticketstore
WHERE logged_in + interval '1 week' < current_date;

-- name: delete-old-cas-oppija-tickets!
DELETE FROM cas_oppija_ticketstore
WHERE logged_in + interval '1 week' < current_date;

