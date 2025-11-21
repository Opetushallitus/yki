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
UPDATE quarantine
SET deleted_at=current_timestamp
WHERE id = :id
  AND deleted_at IS NULL;

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
  AND NOT EXISTS (SELECT qr.id
                  FROM quarantine_review qr
                  WHERE qr.registration_id = r.id
                    AND qr.quarantine_id = q.id
                    AND q.updated <= qr.updated)
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

-- name: select-exam-session-organizer-oid
-- single?: true
SELECT o.oid
FROM exam_session es
INNER JOIN organizer o ON es.organizer_id = o.id
INNER JOIN registration r ON r.exam_session_id = es.id
WHERE r.id = :id;

-- name: select-exam-sessions
SELECT
  e.id,
  language_code,
  level_code,
  ed.exam_date AS session_date,
  e.max_participants,
  ed.registration_start_date,
  ed.registration_end_date,
  e.office_oid,
  e.published_at,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id
     AND re.kind = 'QUEUE'
     AND re.state IN ('SUBMITTED', 'STARTED')) AS queue,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id
     AND re.kind = 'ADMISSION'
     AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) AS participants,
  o.oid AS organizer_oid,
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
         WHERE exam_session_id = e.id) loc) AS location,
  within_dt_range(now(), ed.registration_start_date, ed.registration_end_date) AS open,
  (now() AT TIME ZONE 'Europe/Helsinki' <
    (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') +
     time '16:00')) AS upcoming_admission,
  select_registration_kind(e.id) AS available_registration_kind
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
  e.office_oid,
  e.published_at,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id
     AND re.kind = 'QUEUE'
     AND re.state IN ('SUBMITTED', 'STARTED'))                                 AS queue,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id
     AND re.kind = 'ADMISSION'
     AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED'))                    as participants,
  (SELECT COUNT(1)
   FROM registration re
   WHERE re.exam_session_id = e.id
     AND re.kind = 'POST_ADMISSION'
     AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED'))                    as pa_participants,
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
 ) AS contact,
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
    WHERE exam_session_id = e.id) loc) AS location,
       within_dt_range(now(), ed.registration_start_date, ed.registration_end_date) as open,
       (now() AT TIME ZONE 'Europe/Helsinki' <
        (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') +
         time '16:00'))                                                             AS upcoming_admission
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE ed.exam_date >= COALESCE(:from, ed.exam_date)
  AND o.oid = :oid
ORDER BY ed.exam_date ASC;

-- name: select-registration-details-for-transfer
SELECT es.id, ed.exam_date
FROM registration re
INNER JOIN exam_session es ON re.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE re.id = :id;

-- name: select-transfer-targets-by-exam-session-id
SELECT ies.id, ied.exam_date
FROM exam_session es
LEFT JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN exam_session ies ON ies.id <> es.id AND ies.level_code = es.level_code AND ies.language_code = es.language_code AND ies.organizer_id = es.organizer_id
LEFT JOIN exam_date ied ON ies.exam_date_id = ied.id
WHERE es.id = :exam_session_id  AND ied.exam_date >= ed.exam_date;

-- name: select-exam-session-by-id
SELECT
  e.id,
  ed.exam_date AS session_date,
  ed.registration_start_date,
  ed.registration_end_date,
  e.language_code,
  e.level_code,
  e.max_participants,
  e.office_oid,
  e.published_at,
(SELECT COUNT(1)
 FROM registration re
 WHERE re.exam_session_id = e.id
   AND re.kind = 'QUEUE'
   AND re.state IN ('SUBMITTED', 'STARTED')) AS queue,
(SELECT COUNT(1)
 FROM registration re
 WHERE re.exam_session_id = e.id
   AND re.kind = 'ADMISSION'
   AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) AS participants,
(SELECT COUNT(1)
 FROM registration re
 WHERE re.exam_session_id = e.id
   AND re.kind = 'POST_ADMISSION'
   AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED')) AS pa_participants,
o.oid AS organizer_oid,
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
 ) AS contact,
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
    WHERE exam_session_id = e.id) loc) AS location,
(now() AT TIME ZONE 'Europe/Helsinki' <
 (date_trunc('day', ed.registration_end_date AT TIME ZONE 'Europe/Helsinki') +
  time '16:00')) AS upcoming_admission,
within_dt_range(now(), ed.registration_start_date, ed.registration_end_date) AS open,
select_registration_kind(e.id) AS available_registration_kind
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
   within_dt_range(now(), ed.registration_start_date, ed.registration_end_date) AS open
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

-- name: update-registration-participant-id!
UPDATE registration
SET participant_id = :participant_id
WHERE id = :registration_id;

-- name: update-participant-external-id!
UPDATE participant
SET external_user_id = :external_user_id
WHERE id = :id;

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
  expires_at,
  user_data
) VALUES (
  :code,
  :type::login_link_type,
  :participant_id,
  :exam_session_id,
  :registration_id,
  :expired_link_redirect,
  :success_redirect,
  :expires_at,
  :user_data
);

-- name: renew-user-portal-link<!
INSERT INTO login_link(
  code,
  type,
  participant_id,
  exam_session_id,
  registration_id,
  expired_link_redirect,
  success_redirect,
  expires_at,
  user_data)
SELECT
  :new_code,
  type,
  participant_id,
  exam_session_id,
  registration_id,
  expired_link_redirect,
  success_redirect,
  :expires_at,
  user_data
FROM login_link where type = 'PERSON' AND code = :old_code;

-- name: select-recent-login-link-by-exam-session-and-participant-id
SELECT l.id, l.created
FROM login_link l
WHERE l.exam_session_id = :exam_session_id
AND   l.participant_id = :participant_id
AND   l.created > :older_than;

-- name: select-login-link-by-code
SELECT
 l.code,
 pa.external_user_id,
 pa.email AS participant_email,
 pe.email AS person_email,
 l.exam_session_id,
 l.expires_at,
 l.expired_link_redirect,
 l.success_redirect,
 l.registration_id,
 l.type,
 l.user_data,
 r.person_oid
FROM login_link l
INNER JOIN participant pa
  ON l.participant_id = pa.id
LEFT JOIN registration r
  ON l.registration_id = r.id
LEFT JOIN person pe ON r.person_oid = pe.oid
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
  :kind::registration_kind
  -- only one registration per participant on same exam date
  WHERE NOT EXISTS (SELECT es.id
                    FROM exam_session es
                    INNER JOIN registration re ON es.id = re.exam_session_id
                    WHERE re.participant_id = :participant_id
                      AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
                      AND es.exam_date_id =
                        (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id));

-- name: update-started-registration-oid!
UPDATE registration
SET person_oid=:oid,
    modified=current_timestamp
WHERE id=:id AND state='STARTED';

-- name: select-person-has-other-registrations-for-same-day
WITH exam_sessions_for_same_day AS (
    SELECT es2.id
    FROM registration r
    INNER JOIN exam_session es ON r.exam_session_id = es.id
    INNER JOIN exam_date ed ON es.exam_date_id = ed.id
    INNER JOIN exam_session es2 ON ed.id = es2.exam_date_id
    WHERE r.id = :id
)
SELECT EXISTS (
    SELECT r.id
    FROM registration r
    WHERE r.id <> :id
      AND r.person_oid = :oid
      AND r.exam_session_id IN (SELECT id FROM exam_sessions_for_same_day)
      AND r.state IN ('SUBMITTED', 'COMPLETED')
    );

-- name: update-registration-to-submitted!
WITH exam_sessions_for_same_day AS (
    SELECT es2.id
    FROM registration r
    INNER JOIN exam_session es ON r.exam_session_id = es.id
    INNER JOIN exam_date ed ON es.exam_date_id = ed.id
    INNER JOIN exam_session es2 ON ed.id = es2.exam_date_id
    WHERE r.id = :id
)
UPDATE registration SET
  state = :to_state::registration_state,
  modified = current_timestamp,
  form = :form,
  person_oid = :oid,
  form_version = :form_version,
  expires_at = :expires_at,
  exam_fee = :exam_fee,
  ui_language = :ui_language
WHERE
  id = :id
  AND state = 'STARTED'
  AND participant_id = :participant_id
  AND NOT EXISTS (
      SELECT r.id FROM registration r
      WHERE r.id <> :id AND
            r.person_oid = :oid AND
            r.exam_session_id IN (SELECT id FROM exam_sessions_for_same_day) AND
            r.state IN ('SUBMITTED', 'COMPLETED'));

-- name: cancel-started-registration-for-participant!
UPDATE registration SET
  state = 'CANCELLED',
  modified = current_timestamp
WHERE
  id = :id AND
  participant_id = :participant_id AND
  state = 'STARTED';

-- name: select-exam-session-registration-open
SELECT exam_session_registration_open(:exam_session_id) as exists;

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
    HAVING (es.max_participants - COUNT(re.id)) <= 0)
AS exists;

-- name: select-participant-registered-to-other-exam-on-exam-date
SELECT es.id, re.state
FROM exam_session es
INNER JOIN registration re ON es.id = re.exam_session_id
WHERE re.participant_id = :participant_id
  AND re.state IN ('COMPLETED', 'SUBMITTED')
  AND es.exam_date_id = (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id)
  AND es.id <> :exam_session_id;

-- name: select-participant-registered-to-exam-on-exam-date
SELECT es.id, re.state
FROM exam_session es
INNER JOIN registration re ON es.id = re.exam_session_id
WHERE re.participant_id = :participant_id
  AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
  AND es.exam_date_id = (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id);

-- name: select-person-registered-to-exam-on-exam-date
SELECT es.id
FROM registration r
INNER JOIN registration r2 ON r.person_oid = r2.person_oid
INNER JOIN exam_session es ON es.id = r2.exam_session_id
WHERE r.id = :registration_id
  AND r2.id <> r.id
  AND r2.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
  AND es.exam_date_id = (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id);

-- name: select-started-registration-id-and-kind-by-participant
SELECT re.id, re.kind
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

-- Select registrations to expire. This routine only expires actual registrations (kind='ADMISSION').
-- Queued registrations are expired with below query.
-- name: select-submitted-registrations-to-expire
SELECT id FROM registration
WHERE state = 'SUBMITTED'
  AND kind = 'ADMISSION'
  AND expires_at < current_timestamp;

-- queuing period ends one week before exam date
-- name: select-queued-registrations-to-expire
SELECT r.id FROM registration r
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE r.kind = 'QUEUE'
  AND r.state IN ('STARTED', 'SUBMITTED')
  AND ed.exam_date < (current_date + interval '1 week');

-- name: expire-registrations-by-ids!
UPDATE registration
SET state = 'EXPIRED',
    modified = current_timestamp
WHERE id IN (:ids) AND state IN ('STARTED', 'SUBMITTED');

-- name: update-registration-exam-session!
WITH unavailable_exam_sessions_for_person AS (
    SELECT es2.id
    FROM registration r
    INNER JOIN registration r2 ON r2.person_oid = r.person_oid
    INNER JOIN exam_session es ON r2.exam_session_id = es.id
    INNER JOIN exam_date ed ON es.exam_date_id = ed.id
    INNER JOIN exam_session es2 ON es2.exam_date_id = ed.id
    WHERE r.id = :registration_id AND
        r2.id <> r.id AND
        r2.state IN ('STARTED', 'SUBMITTED', 'COMPLETED'))
UPDATE registration
SET exam_session_id = :exam_session_id,
    kind = 'ADMISSION',
    original_exam_session_id = exam_session_id,
    is_transfered = TRUE
WHERE id = :registration_id
AND EXISTS (SELECT id
            FROM exam_session
            WHERE id = :exam_session_id
              AND organizer_id IN (SELECT id FROM organizer WHERE oid = :oid))
AND :exam_session_id NOT IN (SELECT id FROM unavailable_exam_sessions_for_person);

-- name: relocate-registration-for-user<!
WITH unavailable_exam_sessions_for_person AS (
    SELECT es2.id
    FROM registration r
    INNER JOIN registration r2 ON r2.person_oid = r.person_oid
    INNER JOIN exam_session es ON r2.exam_session_id = es.id
    INNER JOIN exam_date ed ON es.exam_date_id = ed.id
    INNER JOIN exam_session es2 ON es2.exam_date_id = ed.id
    WHERE r.id = :registration_id AND
          r2.id <> r.id AND
          r2.state IN ('STARTED', 'SUBMITTED', 'COMPLETED'))
UPDATE registration
SET exam_session_id = :target_id,
    original_exam_session_id = exam_session_id,
    is_transfered = TRUE,
    modified = current_timestamp
WHERE id = :registration_id AND
      person_oid = :person_oid AND
      :target_id NOT IN (SELECT id FROM unavailable_exam_sessions_for_person) AND
      TRUE IN (SELECT is_transferable(r.id) FROM registration r WHERE id = :registration_id);

-- name: select-registration-data
SELECT re.id,
       re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       re.expires_at,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name,
       p.external_user_id = p.email AS is_email_auth,
       pe.email,
       fr.free_registration_id
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
LEFT JOIN participant p ON re.participant_id = p.id
LEFT JOIN person pe ON re.person_oid = pe.oid
LEFT JOIN free_registration fr ON fr.registration_id = re.id
WHERE re.id = :id
  AND (re.kind IN ('ADMISSION', 'QUEUE'))
  AND ((re.state IN ('STARTED', 'SUBMITTED'))
       OR (re.state IN ('COMPLETED') AND fr.free_registration_id IS NOT NULL))
  AND esl.lang = :lang
  AND re.participant_id = :participant_id;

-- name: select-registration-and-exam-session-state
SELECT re.state, exam_session_registration_open(es.id) AS open
FROM registration re
INNER JOIN exam_session es on re.exam_session_id = es.id
WHERE re.id = :id;

-- name: select-registration-data-by-participant
SELECT re.id,
       re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       re.expires_at,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND (re.kind IN ('ADMISSION', 'QUEUE'))
  AND (re.state IN ('STARTED', 'SUBMITTED'))
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
       pe.email,
       pe.last_name,
       pe.first_name,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name,
       p.external_user_id = p.email AS is_email_auth
FROM registration re
INNER JOIN person pe ON re.person_oid = pe.oid
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
INNER JOIN participant p ON p.id = re.participant_id
WHERE re.id = :id
  AND re.exam_session_id = :exam_session_id
  AND re.state = 'COMPLETED'
  AND esl.lang = :lang;

-- name: select-registration-details-for-clerk-mail
SELECT re.state,
       re.exam_session_id,
       re.participant_id,
       re.kind,
       pe.email,
       pe.last_name,
       pe.first_name,
       re.form->>'certificate_lang' AS lang,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       esl.extra_information,
       esl.street_address,
       esl.post_office,
       esl.zip,
       esl.name,
       p.external_user_id = p.email AS is_email_auth
FROM registration re
INNER JOIN person pe ON pe.oid = re.person_oid
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
LEFT JOIN participant p ON re.participant_id = p.id
WHERE re.id = :id
  AND re.exam_session_id = :exam_session_id
  AND re.state IN ('COMPLETED', 'PAID_AND_CANCELLED', 'SUBMITTED', 'CANCELLED')
  ORDER BY CASE
      WHEN esl.lang = re.form->>'certificate_lang' THEN 1
      WHEN esl.lang = 'fi' THEN 2
      WHEN esl.lang = 'en' THEN 3 ELSE 4 END;

-- name: select-completed-registration-lang
SELECT re.form->>'certificate_lang' AS certificate_lang
FROM registration re
WHERE re.id = :id;

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
       re.state,
       pe.first_name,
       pe.last_name,
       p.email,
       p.external_user_id,
       esl.name,
       es.language_code,
       es.level_code,
       es.organizer_id,
       ed.exam_date
FROM registration re
INNER JOIN person pe ON re.person_oid = pe.oid
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
SELECT pa.id AS participant_id,
       pe.email,
       es.language_code,
       es.level_code,
       esl.name,
       esl.street_address,
       esl.zip,
       esl.post_office,
       ed.exam_date,
       re.form->>'last_name' AS last_name,
       re.form->>'first_name' AS first_name,
       pa.external_user_id = pa.email AS is_email_auth
FROM registration re
INNER JOIN participant pa ON pa.id = re.participant_id
INNER JOIN person pe ON pe.oid = re.person_oid
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
  AND (current_date + interval '7 day') <= ed.exam_date
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

-- Synchronization is done during registration period and
-- failed sync attempts will be retried for given period
-- after registration has ended.
-- TODO Can we simplify sync attempts? If sync attempts fail only very rarely,
--  it seems we could do with just good enough monitoring instead!
-- Exam sessions where participants have been relocated to another
-- session after the registration has ended, are synced and retried
-- for one day after the relocation.

-- name: select-exam-sessions-to-be-synced
SELECT es.id as exam_session_id, pss.created
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN participant_sync_status pss ON pss.exam_session_id = es.id
WHERE (((ed.exam_date >= (current_date + interval '1 week' - :duration::interval)
    OR ((ed.registration_end_date + :duration::interval) >= current_date
        AND pss.failed_at IS NOT NULL
        AND (pss.success_at IS NULL OR pss.failed_at > pss.success_at)))
    AND ed.registration_start_date <= current_date)
    OR (pss.relocated_at IS NOT NULL
        AND pss.success_at IS NULL
        AND ed.registration_start_date < current_date
        AND (pss.relocated_at + interval '1 day') > current_date))
  -- TODO Consider removing the below condition!
  --  Should be able to also notify Solki of exam sessions that have become empty.
  AND (SELECT COUNT(1)
       FROM registration re
       WHERE re.exam_session_id = es.id
         AND re.state = 'COMPLETED') > 0;

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
SELECT r.form, r.person_oid, r.is_transfered, p.last_name, p.first_name, p.email, p.zip, p.post_office, p.street_address
FROM registration r
INNER JOIN person p ON p.oid = r.person_oid
WHERE r.exam_session_id = :id
AND r.state = 'COMPLETED';

-- name: select-exam-session-participants
SELECT
  r.created,
  r.form,
  p.last_name,
  p.first_name,
  p.email,
  p.phone_number,
  p.zip,
  p.post_office,
  p.street_address,
  r.state,
  r.id AS registration_id,
  r.kind,
  r.original_exam_session_id,
  oed.exam_date AS original_exam_date,
  (r.state = 'COMPLETED' AND NOT r.is_transfered) AS is_transferable,
  r.is_transfered,
  fr.free_registration_id IS NOT NULL AS is_free_registration,
  fr.source AS free_registration_source,
  fr.type AS free_registration_basis,
  fr.is_foreign AS free_registration_is_foreign
FROM exam_session es
INNER JOIN registration r ON es.id = r.exam_session_id
INNER JOIN person p ON r.person_oid = p.oid
LEFT JOIN exam_session oes ON oes.id = r.original_exam_session_id
LEFT JOIN exam_date oed ON oed.id = oes.exam_date_id
LEFT JOIN free_registration fr ON fr.registration_id = r.id
WHERE es.id = :id
AND es.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid)
AND r.state != 'STARTED'
AND r.form IS NOT NULL
ORDER BY r.created, r.id ASC;

-- name: select-participant-and-queue-count-by-exam-session
SELECT es.id AS exam_session_id,
       es.max_participants,
       (SELECT COUNT(*)
        FROM registration r
        WHERE r.kind = 'ADMISSION'
          AND r.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
          AND r.exam_session_id = es.id) AS participants,
       (SELECT COUNT(*)
        FROM registration r
        WHERE r.kind = 'QUEUE'
          AND r.state IN ('SUBMITTED', 'STARTED')
          AND r.exam_session_id = es.id) AS queue
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE
    within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
    OR (ed.registration_end_date <= current_date AND
        current_date + interval '1 week' <= ed.exam_date);

-- name: lift-registration-from-queue<!
WITH registrations_to_update AS (SELECT id, free_registration_id
                                 FROM registration
                                 LEFT JOIN free_registration ON registration_id = id
                                 WHERE kind = 'QUEUE'
                                   AND state IN ('SUBMITTED')
                                   AND exam_session_id = :exam_session_id
                                 ORDER BY created ASC
                                 LIMIT 1)
UPDATE registration
SET kind                 = 'ADMISSION',
    state                = CASE WHEN free_registration_id IS NOT NULL
                                     THEN 'COMPLETED'::registration_state
                                     ELSE 'SUBMITTED'::registration_state
                           END,
    lifted_from_queue_at = current_timestamp,
    expires_at           = CASE WHEN free_registration_id IS NULL
                                     THEN at_midnight((current_date + '1 day'::interval)::date)
                                     ELSE expires_at
                           END
    FROM registrations_to_update
WHERE registration.id = registrations_to_update.id

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

-- name: select-organizer-exam-dates
SELECT
ed.id,
ed.exam_date,
ed.registration_start_date,
ed.registration_end_date,
(SELECT array_to_json(array_agg(lang))
 FROM (SELECT language_code, level_code
       FROM exam_date_language
       WHERE exam_date_id = ed.id
         AND deleted_at IS NULL) lang) AS languages,
(SELECT COUNT(1)
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
  (SELECT array_to_json(array_agg(lang))
   FROM (SELECT language_code, level_code
         FROM exam_date_language
         WHERE exam_date_id = ed.id
           AND deleted_at IS NULL) lang) AS languages,
  (SELECT COUNT(1)
   FROM exam_session
   WHERE exam_date_id = ed.id) AS exam_session_count,
  ev.evaluation_start_date,
  ev.evaluation_end_date
FROM exam_date ed
LEFT JOIN evaluation ev ON ev.exam_date_id = ed.id
WHERE ed.id = :id
  AND ed.deleted_at IS NULL;


-- name: select-exam-dates-by-date
SELECT
  ed.id,
  ed.exam_date,
  ed.registration_start_date,
  ed.registration_end_date,
  (SELECT array_to_json(array_agg(lang))
   FROM (SELECT language_code, level_code
         FROM exam_date_language
         WHERE exam_date_id = ed.id
           AND deleted_at IS NULL) lang) AS languages
FROM exam_date ed
WHERE ed.exam_date = :exam_date
  AND deleted_at IS NULL;

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
    registration_end_date   = :registration_end_date
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
  AND (ep.evaluation_end_date >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')::DATE);

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
  p.last_name,
  p.first_name,
  p.email,
  es.language_code,
  es.level_code,
  ed.exam_date,
  o.oid,
  oed.exam_date AS original_exam_date
FROM exam_payment_new epn
INNER JOIN registration r ON epn.registration_id = r.id
INNER JOIN person p ON r.person_oid = p.oid
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
INNER JOIN organizer o on es.organizer_id = o.id
LEFT JOIN exam_session oes ON r.original_exam_session_id = oes.id
LEFT JOIN exam_date oed ON oes.exam_date_id = oed.id
WHERE (date_trunc('day', :from_inclusive) AT TIME ZONE 'Europe/Helsinki')::DATE <= epn.paid_at AND
      epn.paid_at < (date_trunc('day', :to_exclusive) AT TIME ZONE 'Europe/Helsinki')::DATE;

-- name: select-free-registrations-for-timerange
SELECT
    0 as amount,
    fr.created_at AS paid_at,
    fr.source,
    fr.is_foreign,
    fr.matriculation_exam,
    fr.higher_education_concluded,
    fr.higher_education_enrolled,
    fr.eb,
    fr.dia,
    fr.other,
    p.last_name,
    p.first_name,
    p.email,
    es.language_code,
    es.level_code,
    ed.exam_date,
    o.oid,
    oed.exam_date AS original_exam_date
FROM registration r
INNER JOIN free_registration fr ON r.id = fr.registration_id
INNER JOIN person p ON r.person_oid = p.oid
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
INNER JOIN organizer o on es.organizer_id = o.id
LEFT JOIN exam_session oes ON r.original_exam_session_id = oes.id
LEFT JOIN exam_date oed ON oes.exam_date_id = oed.id
WHERE r.state = 'COMPLETED' AND
    (date_trunc('day', :from_inclusive) AT TIME ZONE 'Europe/Helsinki')::DATE <= fr.created_at AND
    fr.created_at < (date_trunc('day', :to_exclusive) AT TIME ZONE 'Europe/Helsinki')::DATE;

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

-- name: ensure-person-exists!
INSERT INTO person (oid, first_name, last_name) VALUES (:oid, :first_name, :last_name)
ON CONFLICT (oid, first_name, last_name)
DO NOTHING;

-- name: upsert-person!
INSERT INTO person
(oid, first_name, last_name, email, phone_number, street_address, post_office, zip, nationality_code, gender) VALUES
(:oid, :first_name, :last_name, :email, :phone_number, :street_address, :post_office, :zip, :nationality_code, cast(:gender as gender_code))
ON CONFLICT (oid)
DO UPDATE SET first_name = :first_name, last_name = :last_name,
email = :email, phone_number = :phone_number,
street_address = :street_address,
post_office = :post_office, zip = :zip,
nationality_code = :nationality_code,
gender = cast(:gender as gender_code),
modified = current_timestamp;

-- name: update-person-contact-details!
UPDATE person
SET email = :email,
    phone_number = :phone_number,
    street_address = :street_address,
    post_office = :post_office,
    zip = :zip,
    modified = current_timestamp
WHERE oid = :oid;

-- name: select-person
SELECT oid, first_name, last_name, email, phone_number, street_address, post_office, zip
FROM person
WHERE oid = :oid;

-- name: select-full-person-details
SELECT oid, first_name, last_name, email, phone_number, street_address, post_office, zip, gender, nationality_code
FROM person
WHERE oid = :oid;

-- name: select-person-registrations
SELECT r.id, r.exam_session_id, r.state, r.kind,
ed.exam_date, es.language_code, es.level_code,
ed.registration_start_date, ed.registration_end_date,
       (SELECT array_to_json(array_agg(loc))
        FROM (SELECT name,
                     street_address,
                     post_office,
                     zip,
                     other_location_info,
                     extra_information,
                     lang
              FROM exam_session_location
              WHERE exam_session_id = es.id) loc) as location,
       (SELECT epn.paid_at FROM exam_payment_new epn
        WHERE epn.registration_id = r.id AND
              epn.state = 'PAID') AS paid_at,
       r.expires_at,
       r.exam_fee,
       is_transferable(r.id) AS is_transferable,
       is_cancellable(r.id) AS is_cancellable,
       r.is_transfered,
       r.lifted_from_queue_at,
       fr.free_registration_id IS NOT NULL AS is_free_registration
FROM registration r
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN free_registration fr ON r.id = fr.registration_id
WHERE person_oid = :oid
  AND current_date - interval '1 year' <= ed.exam_date
  AND state <> 'STARTED';

-- name: select-registration-queue-positions
SELECT r.id, COUNT(r2.id) AS position
FROM registration r
INNER JOIN exam_session es ON r.exam_session_id = es.id
JOIN registration r2 ON r2.exam_session_id = es.id
WHERE r2.id <> r.id
AND r2.created < r.created
AND r2.kind = 'QUEUE'
AND r2.state IN ('STARTED', 'SUBMITTED')
AND r.id IN (:ids)
GROUP BY r.id;

-- name: select-registration-relocate-details
SELECT r.id,
       es.id AS exam_session_id,
       is_transferable(r.id) AS is_transferable,
       ed.exam_date AS session_date,
       es.level_code,
       es.language_code,
       (SELECT array_to_json(array_agg(loc))
        FROM (SELECT name,
                     street_address,
                     post_office,
                     zip,
                     other_location_info,
                     extra_information,
                     lang
              FROM exam_session_location
              WHERE exam_session_id = es.id) loc) as location,
        c.email AS contact_email
FROM registration r
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
INNER JOIN exam_session_contact esc ON esc.exam_session_id = es.id
INNER JOIN contact c ON c.id = esc.contact_id
WHERE r.id = :id AND r.person_oid = :oid;

-- name: select-registration-transfer-target-details
WITH exam_sessions_for_same_day AS (
    SELECT es2.id
    FROM registration r
    INNER JOIN registration r2 ON r.person_oid = r2.person_oid
    INNER JOIN exam_session es ON r2.exam_session_id = es.id
    INNER JOIN exam_date ed ON es.exam_date_id = ed.id
    INNER JOIN exam_session es2 ON ed.id = es2.exam_date_id
    WHERE r.id = :registration_id AND
          r.id <> r2.id AND
          r2.state IN ('STARTED', 'SUBMITTED', 'COMPLETED')
)
SELECT
    ies.id,
    ied.exam_date AS session_date,
    ies.level_code,
    ies.language_code,
    (SELECT array_to_json(array_agg(loc))
     FROM (SELECT name,
                  street_address,
                  post_office,
                  zip,
                  other_location_info,
                  extra_information,
                  lang
           FROM exam_session_location
           WHERE exam_session_id = ies.id) loc) as location,
    (SELECT COUNT(1) FROM registration WHERE exam_session_id = ies.id AND state IN ('STARTED','SUBMITTED', 'COMPLETED') AND kind = 'ADMISSION') AS participants,
    ies.max_participants
FROM exam_session es
LEFT JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN exam_session ies ON ies.id <> es.id AND ies.level_code = es.level_code AND ies.language_code = es.language_code AND ies.organizer_id = es.organizer_id
LEFT JOIN exam_date ied ON ies.exam_date_id = ied.id
WHERE es.id = :exam_session_id
  AND ied.exam_date >= ed.exam_date
  AND select_registration_kind(ies.id) = 'ADMISSION'
  AND ies.id NOT IN (SELECT id FROM exam_sessions_for_same_day);

-- name: select-persons-without-gender-or-nationality
WITH person_oids AS (
    SELECT p.oid
    FROM person p
    WHERE p.gender IS NULL OR p.nationality_code IS NULL
    ORDER BY p.created DESC
    LIMIT 2000
) SELECT DISTINCT ON (r.person_oid)
      r.person_oid,
      r.form
  FROM registration r
  WHERE
      r.person_oid IN (SELECT oid FROM person_oids) AND
      (COALESCE(r.form->>'gender','') <> ''
           OR
       COALESCE(r.form->>'ssn','') <> ''
           OR
      r.form->>'nationalities' IS NOT NULL)
      ORDER BY r.person_oid, r.created DESC;

-- name: update-person-gender-and-nationality!
UPDATE person
SET gender = cast(:gender as gender_code),
    nationality_code = :nationality_code
WHERE oid = :oid;

-- name: select-registration-to-confirm-details
SELECT r.id,
       r.exam_fee,
       r.expires_at,
       es.language_code,
       es.level_code,
       ed.registration_start_date,
       ed.registration_end_date,
       ed.exam_date AS session_date,
       (SELECT array_to_json(array_agg(loc))
        FROM (SELECT name,
                     street_address,
                     post_office,
                     zip,
                     other_location_info,
                     extra_information,
                     lang
              FROM exam_session_location
              WHERE exam_session_id = es.id) loc) as location
FROM registration r
INNER JOIN exam_session es ON r.exam_session_id = es.id
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE r.id = :id
  AND r.person_oid = :oid
  AND r.state = 'SUBMITTED'
  AND r.kind = 'ADMISSION';

-- name: cancel-registration-for-person<!
UPDATE registration
SET state = CASE WHEN state = 'COMPLETED'::registration_state
                 THEN 'PAID_AND_CANCELLED'::registration_state
                 ELSE 'CANCELLED'::registration_state END,
    modified=current_timestamp
WHERE person_oid = :oid
  AND id = :id
  AND state IN ('COMPLETED', 'SUBMITTED')
  AND TRUE IN (SELECT is_cancellable(r.id) FROM registration r WHERE id = :id);

-- name: schedule-person-to-be-synced!
INSERT INTO person_sync_status (person_oid) VALUES (:oid);

-- name: mark-successful-person-sync-attempt!
UPDATE person_sync_status SET success_at=current_timestamp, should_retry=false WHERE id=:id;

-- name: mark-failed-person-sync-attempt!
UPDATE person_sync_status SET failed_at=current_timestamp, should_retry=:should_retry WHERE id=:id;

-- name: select-persons-to-sync
SELECT pss.id, pss.person_oid
FROM person_sync_status pss
WHERE current_timestamp < pss.created + :duration::interval
  AND pss.success_at IS NULL
  AND (pss.should_retry IS NULL
      OR pss.should_retry IS true);

-- name: select-free-registration
SELECT free_registration_id,
       source,
       type,
       matriculation_exam,
       higher_education_concluded,
       higher_education_concluded,
       eb,
       dia,
       other
FROM free_registration
WHERE registration_id = :id
