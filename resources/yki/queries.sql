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

-- name: select-organizers-by-oids
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
WHERE deleted_at IS NULL
  AND o.oid IN (:oids);

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
  exam_language_id,
  exam_date_id,
  max_participants,
  published_at
) VALUES (
  (SELECT id FROM organizer
    WHERE oid = :oid AND deleted_at IS NULL AND agreement_end_date >= :session_date AND agreement_start_date <= :session_date),
  (SELECT id FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  (SELECT id from exam_date WHERE exam_date = :session_date),
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
INSERT INTO exam_date(
  exam_date,
  registration_start_date,
  registration_end_date
) VALUES (
  :exam_date,
  :registration_start_date,
  :registration_end_date
);

-- name: select-exam-sessions
SELECT
  e.id,
  el.language_code,
  el.level_code,
  ed.exam_date AS session_date,
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
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_language el ON e.exam_language_id = el.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE ed.exam_date >= COALESCE(:from, ed.exam_date)
  AND o.oid = COALESCE(:oid, o.oid);

-- name: select-exam-session-by-id
SELECT
  e.id,
  ed.exam_date,
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
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE e.id = :id;

-- name: update-exam-session!
UPDATE exam_session
SET
  exam_date_id = (SELECT id FROM exam_date WHERE exam_date = :session_date),
  exam_language_id =
  (SELECT id FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer
                              WHERE oid = :oid
                                AND deleted_at IS NULL AND agreement_end_date >= :session_date
                                AND agreement_start_date <= :session_date)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  max_participants = :max_participants,
  published_at = :published_at,
  modified = current_timestamp
WHERE id = :id;

-- name: delete-exam-session-location!
DELETE FROM exam_session_location
WHERE exam_session_id = :id;

-- name: delete-exam-session!
DELETE FROM exam_session
WHERE id = :id;

-- name: insert-participant!
INSERT INTO participant(
  external_user_id
) VALUES (
  :external_user_id
) ON CONFLICT (external_user_id) DO NOTHING;

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
  (SELECT id FROM participant WHERE external_user_id = :email),
  :exam_session_id,
  :registration_id,
  :expired_link_redirect,
  :success_redirect,
  :expires_at
);

-- name: select-login-link-by-code
SELECT
 l.code,
 p.external_user_id,
 l.exam_session_id,
 l.expires_at,
 l.expired_link_redirect,
 l.success_redirect
FROM login_link l INNER JOIN participant p
  ON l.participant_id = p.id
WHERE l.code = :code;

-- name: try-to-acquire-lock!
UPDATE task_lock SET
  last_executed = current_timestamp,
  worker_id = :worker_id
WHERE task = :task
  AND last_executed < (current_timestamp - :interval::interval);

-- name: select-registration
SELECT state, exam_session_id, participant_id
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
WHERE re.id = :id AND p.external_user_id = :external_user_id;

-- name: update-registration!
UPDATE registration
SET
  state = :state::registration_state,
  modified = current_timestamp
WHERE
 id = (SELECT registration_id FROM payment WHERE order_number = :order_number) AND
 state != 'COMPLETED';

-- name: insert-payment<!
INSERT INTO payment(
  state,
  registration_id,
  amount,
  order_number
) VALUES (
  'UNPAID',
  :registration_id,
  :amount,
  :order_number
);

-- name: select-payment-by-registration-id
SELECT
  state,
  registration_id,
  amount,
  reference_number,
  order_number,
  external_payment_id,
  payment_method,
  payed_at
 FROM payment
 WHERE registration_id = :registration_id;

-- name: select-participant-email-by-order-number
SELECT email
FROM participant par
INNER JOIN registration re ON re.participant_id = par.id
INNER JOIN payment pay ON re.id = pay.registration_id
WHERE pay.order_number = :order_number;

-- name: select-next-order-number-suffix
SELECT nextval('payment_order_number_seq');

-- name: update-payment!
 UPDATE payment
 SET
    state = :state::payment_state,
    external_payment_id = :external_payment_id,
    payment_method = :payment_method,
    reference_number = :reference_number,
    payed_at = :payed_at,
    modified = current_timestamp
WHERE
  order_number = :order_number;
