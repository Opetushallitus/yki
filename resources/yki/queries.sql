-- name: select-organizers
SELECT oid, agreement_start_date, agreement_end_date, contact_name, contact_email, contact_phone_number,
(
  SELECT ARRAY_TO_JSON(ARRAY_AGG(ROW_TO_JSON(lang)))
  FROM (
    SELECT language_code, level_code
    FROM exam_language
    WHERE organizer_id = organizer.oid
  ) lang
) AS languages
FROM organizer;

-- SELECT
--   o.oid,
--   o.agreement_start_date,
--   o.agreement_end_date,
--   o.contact_name,
--   o.contact_email,
--   o.contact_phone_number,
--   ARRAY_AGG(row_to_json((ela.language_code, ela.level_code))) as languages
-- FROM organizer o
-- LEFT JOIN exam_language ela ON o.oid = ela.organizer_id
-- GROUP BY o.oid, ela.organizer_id;

-- name: select-organizer
SELECT
  o.oid,
  o.agreement_start_date,
  o.agreement_end_date,
  o.contact_name,
  o.contact_email,
  o.contact_phone_number
FROM organizer o
WHERE o.oid = :oid;

-- name: insert-organizer!
INSERT INTO organizer (
  oid,
  agreement_start_date,
  agreement_end_date,
  contact_name,
  contact_email,
  contact_phone_number
) VALUES (
  :oid,
  :agreement_start_date,
  :agreement_end_date,
  :contact_name,
  :contact_email,
  :contact_phone_number
);

-- name: update-organizer!
UPDATE organizer
SET
  agreement_start_date = :agreement_start_date,
  agreement_end_date = :agreement_end_date,
  contact_name = :contact_name,
  contact_email = :contact_email,
  contact_phone_number = :contact_phone_number
WHERE oid = :oid;

-- name: delete-organizer!
DELETE FROM organizer WHERE oid = :oid;

-- name: delete-organizer-languages!
DELETE FROM exam_language WHERE organizer_id = :oid;

-- name: insert-organizer-language!
INSERT INTO exam_language (
  language_code,
  level_code,
  organizer_id
) VALUES (
  :language_code,
  :level_code,
  :oid
);

-- name: select-organizer-languages
SELECT el.language_code
FROM exam_language el
WHERE el.organizer_id = oid;
