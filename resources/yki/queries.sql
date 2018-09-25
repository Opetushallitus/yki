-- name: select-organizers
SELECT o.oid, o.agreement_start_date, o.agreement_end_date, o.contact_name, o.contact_email, o.contact_phone_number, o.contact_shared_email,
(
  SELECT ARRAY_TO_JSON(ARRAY_AGG(ROW_TO_JSON(lang)))
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

-- name: insert-exam-session!
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
  (SELECT id FROM organizer WHERE oid = :organizer_oid AND deleted_at IS NULL),
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

-- name: insert-exam-session-location!
INSERT INTO exam_session_date(
  exam_date
) VALUES (
  :exam_date
);
