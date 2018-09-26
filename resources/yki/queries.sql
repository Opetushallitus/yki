-- name: select-organizers
SELECT o.oid, o.agreement_start_date, o.agreement_end_date, o.contact_name, o.contact_email, o.contact_phone_number, o.contact_shared_email,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code, level_code
    FROM exam_language
    WHERE organizer_id = o.id
  ) lang
) AS languages
FROM organizer o
WHERE deleted_at IS NULL;

-- name: select-organizer
SELECT
  o.oid,
  o.agreement_start_date,
  o.agreement_end_date,
  o.contact_name,
  o.contact_email,
  o.contact_phone_number,
  o.contact_shared_email
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
  contact_shared_email
) VALUES (
  :oid,
  :agreement_start_date,
  :agreement_end_date,
  :contact_name,
  :contact_email,
  :contact_phone_number,
  :contact_shared_email
);

-- name: update-organizer!
UPDATE organizer
SET
  agreement_start_date = :agreement_start_date,
  agreement_end_date = :agreement_end_date,
  contact_name = :contact_name,
  contact_email = :contact_email,
  contact_phone_number = :contact_phone_number,
  contact_shared_email = :contact_shared_email,
  modified = current_timestamp
WHERE oid = :oid;

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

-- name: select-organizer-languages
SELECT el.language_code
FROM exam_language el
WHERE el.organizer_id = oid;

-- name: insert-attachment-metadata!
INSERT INTO attachment_metadata (
  external_id,
  organizer_id,
  type
) VALUES (
  :external_id,
  (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL),
  :type
);

-- name: insert-exam-session<!
INSERT INTO exam_session (
  organizer_id,
  session_date,
  session_start_time,
  session_end_time,
  registration_start_date,
  registration_start_time,
  registration_end_date,
  registration_end_time,
  max_participants,
  published_at
) VALUES (
  (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL),
  :session_date,
  :session_start_time,
  :session_end_time,
  :registration_start_date,
  :registration_start_time,
  :registration_end_date,
  :registration_end_time,
  :max_participants,
  :published_at
);

-- name: insert-exam-session-location!
INSERT INTO exam_session_location(
  street_address,
  city,
  other_location_info,
  extra_information,
  language_code,
  exam_session_id
) VALUES (
  :street_address,
  :city,
  :other_location_info,
  :extra_information,
  :language_code,
  :exam_session_id
);

-- name: insert-exam-session-date!
INSERT INTO exam_session_date(
  exam_date
) VALUES (
  :exam_date
);

-- name: select-exam-sessions
SELECT
  e.id,
  e.session_date,
  e.session_start_time,
  e.session_end_time,
  e.registration_start_date,
  e.registration_start_time,
  e.registration_end_date,
  e.registration_end_time,
  e.max_participants,
  e.published_at,
  o.oid as organizer_oid,
(
  SELECT array_to_json(array_agg(loc))
  FROM (
    SELECT
      street_address,
      city,
      other_location_info,
      extra_information,
      language_code
    FROM exam_session_location
    WHERE exam_session_id = e.id
  ) loc
) AS location
FROM exam_session e INNER JOIN organizer o
  ON e.organizer_id = o.id
WHERE e.session_date >= COALESCE(:from, e.session_date)
  AND o.oid = COALESCE(:oid, o.oid);

-- name: update-exam-session!
UPDATE organizer
SET
  agreement_start_date = :agreement_start_date,
  agreement_end_date = :agreement_end_date,
  contact_name = :contact_name,
  contact_email = :contact_email,
  contact_phone_number = :contact_phone_number,
  contact_shared_email = :contact_shared_email,
  modified = current_timestamp
WHERE oid = :oid;
