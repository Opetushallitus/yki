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
WHERE deleted_at IS NULL;

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
);

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
  language_code,
  level_code,
  exam_date_id,
  max_participants,
  office_oid,
  published_at
) VALUES (
  (SELECT id FROM organizer
    WHERE oid = :oid AND deleted_at IS NULL AND agreement_end_date >= :session_date AND agreement_start_date <= :session_date),
  (SELECT language_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  (SELECT level_code FROM exam_language el
    WHERE el.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL)
      AND el.language_code = :language_code
      AND el.level_code = :level_code),
  (SELECT id from exam_date WHERE exam_date = :session_date),
  :max_participants,
  :office_oid,
  :published_at
);

-- name: insert-exam-session-location!
INSERT INTO exam_session_location(
  name,
  address,
  other_location_info,
  extra_information,
  lang,
  exam_session_id
) VALUES (
  :name,
  :address,
  :other_location_info,
  :extra_information,
  :lang,
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
  e.office_oid,
  e.published_at,
 (SELECT COUNT(1)
    FROM registration re
    WHERE re.exam_session_id = e.id AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) as participants,
  o.oid as organizer_oid,
 (
  SELECT array_to_json(array_agg(loc))
  FROM (
    SELECT
      name,
      address,
      other_location_info,
      extra_information,
      lang
    FROM exam_session_location
    WHERE exam_session_id = e.id
  ) loc
 ) as location
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE ed.exam_date >= COALESCE(:from, ed.exam_date)
  AND o.oid = COALESCE(:oid, o.oid)
ORDER BY ed.exam_date ASC;

-- name: select-exam-session-by-id
SELECT
  e.id,
  ed.exam_date AS session_date,
  language_code,
  level_code,
  e.max_participants,
  e.office_oid,
  e.published_at,
  o.oid as organizer_oid,
(
  SELECT array_to_json(array_agg(loc))
  FROM (
    SELECT
      name,
      address,
      other_location_info,
      extra_information,
      lang
    FROM exam_session_location
    WHERE exam_session_id = e.id
  ) loc
) AS location
FROM exam_session e
INNER JOIN organizer o ON e.organizer_id = o.id
INNER JOIN exam_date ed ON e.exam_date_id = ed.id
WHERE e.id = :id;

-- name: select-exam-session-with-location
SELECT
  es.language_code,
  es.level_code,
  ed.exam_date,
  ed.registration_end_date,
  esl.address,
  esl.name
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
WHERE exam_session_id IN (SELECT exam_session_id FROM exam_session_location esl
                          INNER JOIN exam_session es ON es.id = esl.exam_session_id
                          INNER JOIN exam_date ed ON ed.id = es.exam_date_id
                          WHERE exam_session_id = :id AND ed.registration_start_date > current_timestamp);

-- name: delete-exam-session!
DELETE FROM exam_session
WHERE id = (SELECT es.id FROM exam_session es
            INNER JOIN exam_date ed ON ed.id = es.exam_date_id
            WHERE es.id = :id AND ed.registration_start_date > current_timestamp
            AND es.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL));

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

-- name: insert-registration<!
INSERT INTO registration(
  state,
  exam_session_id,
  participant_id,
  started_at
) SELECT
  'STARTED',
  :exam_session_id,
  :participant_id,
  :started_at
  -- only one registration per participant on same exam date
  WHERE NOT EXISTS (SELECT es.id
                    FROM exam_session es
                    INNER JOIN registration re ON es.id = re.exam_session_id
                    WHERE re.participant_id = :participant_id
                      AND re.state != 'EXPIRED'
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

-- name: select-exam-session-registration-open
SELECT EXISTS (
  SELECT es.id
  FROM exam_session es
  INNER JOIN exam_date ed ON es.exam_date_id = ed.id
  WHERE es.id = :exam_session_id
    AND ((ed.registration_start_date + time '08:00') AT TIME ZONE 'Europe/Helsinki') < current_timestamp AT TIME ZONE 'Europe/Helsinki'
    AND ((ed.registration_end_date + time '23:59') AT TIME ZONE 'Europe/Helsinki') > current_timestamp AT TIME ZONE 'Europe/Helsinki'
) as exists;

-- name: select-exam-session-space-left
SELECT NOT EXISTS (
	SELECT es.max_participants
	FROM exam_session es
	LEFT JOIN registration re ON es.id = re.exam_session_id
	WHERE re.exam_session_id = :exam_session_id
	  AND re.state != 'EXPIRED'
	GROUP BY es.max_participants
	HAVING (es.max_participants - COUNT(re.id)) <= 0
) as exists;

-- name: select-participant-not-registered
SELECT NOT EXISTS (
  SELECT es.id
  FROM exam_session es
  INNER JOIN registration re ON es.id = re.exam_session_id
  WHERE re.participant_id = :participant_id
    AND re.state != 'EXPIRED'
    AND es.exam_date_id = (SELECT exam_date_id FROM exam_session WHERE id = :exam_session_id)
) as exists;

-- name: select-registration
SELECT state, exam_session_id, participant_id, es.organizer_id
FROM registration re
INNER JOIN participant p ON p.id = re.participant_id
INNER JOIN exam_session es ON es.id = re.exam_session_id
WHERE re.id = :id AND p.external_user_id = :external_user_id;

-- name: update-started-registrations-to-expired<!
UPDATE registration
SET state = 'EXPIRED',
    modified = current_timestamp
WHERE state = 'STARTED' AND (started_at + interval '1 hour') < current_timestamp
RETURNING id as updated;

-- name: update-registration-exam-session!
UPDATE registration
SET exam_session_id = :exam_session_id
WHERE id = :registration_id
AND EXISTS (SELECT id
            FROM exam_session
            WHERE id = :exam_session_id
              AND organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL))

-- name: update-submitted-registrations-to-expired<!
UPDATE registration
SET state = 'EXPIRED',
    modified = current_timestamp
WHERE state = 'SUBMITTED'
  AND id IN (SELECT registration_id
            FROM payment
            WHERE state = 'UNPAID'
            AND (created + interval '8 days') < current_timestamp)
RETURNING id as updated;

-- name: select-registration-data
SELECT re.state,
       re.exam_session_id,
       re.participant_id,
       es.language_code,
       es.level_code,
       ed.exam_date,
       ed.registration_end_date,
       esl.address,
       esl.name
FROM registration re
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
WHERE re.id = :id
  AND (ed.registration_end_date  + time '23:59') AT TIME ZONE 'Europe/Helsinki' >= (current_timestamp AT TIME ZONE 'Europe/Helsinki')
  AND re.state = 'STARTED'
  AND esl.lang = :lang
  AND re.participant_id = :participant_id;

-- name: update-registration-to-completed!
UPDATE registration
SET
  state = 'COMPLETED',
  modified = current_timestamp
WHERE
 id = (SELECT registration_id FROM payment WHERE order_number = :order_number) AND
 state != 'COMPLETED';

-- name: insert-payment<!
INSERT INTO payment(
  state,
  registration_id,
  amount,
  lang,
  order_number
) VALUES (
  'UNPAID',
  :registration_id,
  :amount,
  :lang,
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

-- name: select-participant-by-external-id
SELECT id, external_user_id, email
FROM participant
WHERE external_user_id = :external_user_id;

-- name: select-participant-by-id
SELECT id, external_user_id, email
FROM participant
WHERE id = :id;

-- name: select-participant-email-by-order-number
SELECT par.email,
       pay.lang
FROM participant par
INNER JOIN registration re ON re.participant_id = par.id
INNER JOIN payment pay ON re.id = pay.registration_id
WHERE pay.order_number = :order_number;

-- name: select-payment-by-order-number
SELECT
  state,
  registration_id,
  amount,
  lang,
  reference_number,
  order_number,
  external_payment_id,
  payment_method,
  payed_at
FROM payment
WHERE order_number = :order_number;

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

-- name: insert-ticket!
INSERT INTO cas_ticketstore (ticket) VALUES (:ticket);

-- name: delete-ticket!
DELETE FROM cas_ticketstore
WHERE ticket = :ticket;

-- name: select-ticket
SELECT ticket
FROM cas_ticketstore
WHERE ticket = :ticket;

--name: insert-participants-sync-status!
INSERT INTO participant_sync_status(
  exam_session_id
) SELECT
  :exam_session_id
  WHERE NOT EXISTS (SELECT exam_session_id
                    FROM participant_sync_status
                    WHERE exam_session_id = :exam_session_id
                      AND success_at IS NULL)
ON CONFLICT DO NOTHING;

-- name: select-not-synced-exam-sessions
SELECT es.id as exam_session_id
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
WHERE ed.registration_end_date < current_date
AND NOT EXISTS (
  SELECT id
  FROM participant_sync_status
  WHERE exam_session_id = es.id
  AND success_at IS NOT NULL
);

-- name: update-participant-sync-to-success!
UPDATE participant_sync_status
SET success_at = current_timestamp
WHERE exam_session_id = :exam_session_id
AND success_at IS NULL;

-- name: select-completed-exam-session-participants
SELECT r.form, r.person_oid
FROM exam_session es
INNER JOIN registration r ON es.id = r.exam_session_id
WHERE es.id = :id
AND r.state = 'COMPLETED';

-- name: select-exam-session-participants
SELECT r.form, r.state, r.id as registration_id
FROM exam_session es
INNER JOIN registration r ON es.id = r.exam_session_id
WHERE es.id = :id
AND es.organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL)
AND r.state IN ('COMPLETED', 'SUBMITTED', 'STARTED');

--name: update-registration-status-to-cancelled!
UPDATE registration
SET state = 'CANCELLED'
WHERE id = :id
AND exam_session_id IN (SELECT id
                        FROM exam_session
                        WHERE organizer_id =
                          (SELECT id
                            FROM organizer
                            WHERE oid = :oid AND deleted_at IS NULL));

-- name: insert-payment-config!
INSERT INTO payment_config(
  organizer_id,
  merchant_id,
  merchant_secret
) VALUES (
  (SELECT id FROM organizer
   WHERE oid = :oid
   AND deleted_at IS NULL),
  :merchant_id,
  :merchant_secret
);

-- name: select-payment-config
SELECT
  organizer_id,
  merchant_id,
  merchant_secret
FROM payment_config
WHERE organizer_id = :organizer_id;

-- name: select-payment-config-by-order-number
SELECT
  pc.organizer_id,
  pc.merchant_id,
  pc.merchant_secret
FROM payment_config pc
INNER JOIN organizer o ON pc.organizer_id = o.id
INNER JOIN exam_session es ON o.id = es.organizer_id
INNER JOIN registration re ON es.id = re.exam_session_id
INNER JOIN payment p ON re.id = p.registration_id
WHERE p.order_number = :order_number;

-- name: update-payment-config!
UPDATE payment_config
SET merchant_secret = :merchant_secret;

-- name: select-exam-dates
SELECT ed.exam_date, ed.registration_start_date, ed.registration_end_date,
(
  SELECT array_to_json(array_agg(lang))
  FROM (
    SELECT language_code
    FROM exam_date_language
    WHERE exam_date_id = ed.id
  ) lang
) AS languages
FROM exam_date ed
WHERE ed.registration_end_date >= current_date
ORDER BY ed.exam_date ASC;
