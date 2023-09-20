-- name: select-contact-emails
SELECT id, email
FROM contact;

-- name: update-contact-email!
UPDATE contact
SET email = :email, modified = current_timestamp
WHERE id = :id;

-- name: select-evaluation-order-emails
SELECT id, email
FROM evaluation_order;

-- name: update-evaluation-order-email!
UPDATE evaluation_order
SET email = :email
WHERE id = :id;

-- name: select-exam-session-queue-emails
SELECT id, email
FROM exam_session_queue;

-- name: update-exam-session-queue-email!
UPDATE exam_session_queue
SET email = :email
WHERE id = :id;

-- name: select-organizer-emails
SELECT id, contact_email
FROM organizer;

-- name: update-organizer-email!
UPDATE organizer
SET contact_email = :email, modified = current_timestamp
WHERE id = :id;

-- name: select-participant-emails
SELECT id, email
FROM participant;

-- name: update-participant-email!
UPDATE participant
SET email = :email
WHERE id = :id;

-- name: select-quarantine-emails
SELECT id, email
FROM quarantine;

-- name: update-quarantine-email!
UPDATE quarantine
SET email = :email, updated = current_timestamp
WHERE id = :id;

-- name: select-registration-emails
SELECT id, form->>'email' AS email
FROM registration;

-- name: update-registration-email!
UPDATE registration
SET form = jsonb_set(form, '{email}'::text[], concat('"', :email, '"')::jsonb), modified=current_timestamp
WHERE id = :id;
