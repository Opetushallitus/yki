-- Needed for tests to pass
ALTER TABLE exam_session
    ADD COLUMN IF NOT EXISTS contact_name text,
    ADD COLUMN IF NOT EXISTS contact_email text,
    ADD COLUMN IF NOT EXISTS contact_phone_number text;


UPDATE exam_session es
SET
  contact_name         = c.name,
  contact_email        = c.email,
  contact_phone_number = c.phone_number
FROM exam_session_contact esc
JOIN contact c ON c.id = esc.contact_id AND c.deleted_at IS NULL
WHERE esc.exam_session_id = es.id
  AND esc.deleted_at IS NULL
  AND es.contact_name IS NULL
  AND es.contact_email IS NULL
  AND es.contact_phone_number IS NULL
  AND esc.id = (
    SELECT MAX(esc2.id)
    FROM exam_session_contact esc2
    JOIN contact c2 ON c2.id = esc2.contact_id AND c2.deleted_at IS NULL
    WHERE esc2.exam_session_id = es.id
      AND esc2.deleted_at IS NULL
  );
