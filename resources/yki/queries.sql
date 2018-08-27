-- name: select-organizers
SELECT 
  o.oid,
  o.agreement_start_date,
  o.agreement_end_date,
  o.contact_name,
  o.contact_email,
  o.contact_phone_number,
  ARRAY_AGG(DISTINCT ela.language_code) as languages,
  ARRAY_AGG(DISTINCT  ele.level_id) as levels
FROM organizer o
LEFT JOIN exam_language ela ON o.oid = ela.organizer_id
LEFT JOIN exam_level ele ON o.oid = ele.organizer_id
GROUP BY o.oid, ela.organizer_id, ele.organizer_id;

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

-- name: delete-organizer!
DELETE FROM organizer WHERE oid = :oid;

-- name: select-organizer-languages
SELECT 
  el.language_code
FROM exam_language el
WHERE el.organizer_id = oid;