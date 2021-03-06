-- name: select-organizers
SELECT o.oid, o.agreement_start_date, o.agreement_end_date, o.contact_name, o.contact_email, o.contact_phone_number, o.extra,
  (
    SELECT array_to_json(array_agg(lang))
    FROM (
      SELECT language_code, level_code
      FROM exam_language
      WHERE organizer_id = o.id
    ) lang
  ) AS languages,
    (
    SELECT array_to_json(array_agg(attachment))
    FROM (
      SELECT external_id, created
      FROM attachment_metadata
      WHERE organizer_id = o.id
      ORDER BY created DESC
    ) attachment
  ) AS attachments,
  json_build_object('merchant_id', pc.merchant_id)::jsonb || json_build_object('merchant_secret', pc.merchant_secret)::jsonb as merchant
FROM organizer o
LEFT JOIN payment_config pc ON pc.organizer_id = o.id
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
  ) AS languages,
  (
    SELECT array_to_json(array_agg(attachment))
    FROM (
      SELECT external_id, created
      FROM attachment_metadata
      WHERE organizer_id = o.id
      ORDER BY created DESC
    ) attachment
  ) AS attachments,
  json_build_object('merchant_id', pc.merchant_id)::jsonb || json_build_object('merchant_secret', pc.merchant_secret)::jsonb as merchant
FROM organizer o
LEFT JOIN payment_config pc ON pc.organizer_id = o.id
WHERE o.deleted_at IS NULL
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

-- name: select-attachment-metadata
SELECT
  external_id,
  type
FROM attachment_metadata
WHERE external_id = :external_id
  AND organizer_id = (SELECT id FROM organizer WHERE oid = :oid AND deleted_at IS NULL);

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
  (SELECT id from exam_date WHERE exam_date = :session_date),
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
  e.post_admission_start_date,
  e.post_admission_quota,
  e.post_admission_active,
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
    WHERE re.exam_session_id = e.id AND re.kind IN ('ADMISSION', 'OTHER') AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')) as participants,
  (SELECT COUNT(1)
    FROM registration re
    WHERE re.exam_session_id = e.id AND re.kind = 'POST_ADMISSION' AND re.state in ('COMPLETED', 'SUBMITTED', 'STARTED')) as pa_participants,
  o.oid as organizer_oid,
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
  within_dt_range(now(), ed.registration_start_date, ed.registration_end_date)
  OR (within_dt_range(now(), e.post_admission_start_date, ed.post_admission_end_date) AND e.post_admission_active = TRUE) as open
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
  ed.registration_start_date,
  ed.registration_end_date,
  e.post_admission_start_date,
  ed.post_admission_end_date,
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
    WHERE re.exam_session_id = e.id AND re.state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
) AS participants,
  o.oid as organizer_oid,
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
  OR (within_dt_range(now(), e.post_admission_start_date, ed.post_admission_end_date) AND e.post_admission_active)) as open
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

-- name: update-started-registrations-to-expired<!
UPDATE registration
SET state = 'EXPIRED',
    modified = current_timestamp
WHERE state = 'STARTED' AND (started_at + interval '30 minutes') < current_timestamp
RETURNING id as updated;

-- name: update-registration-exam-session!
UPDATE registration
SET exam_session_id = :exam_session_id,
    kind = 'ADMISSION',
    original_exam_session_id = (SELECT exam_session_id
                                FROM registration
                                WHERE id = :registration_id)
WHERE id = (SELECT re.id
            FROM registration re
            WHERE re.id = :registration_id)
AND EXISTS (SELECT id
            FROM exam_session
            WHERE id = :exam_session_id
              AND max_participants > (SELECT COUNT(1)
                                      FROM registration
              	                      WHERE exam_session_id = :exam_session_id
	                                    AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED'))
              AND organizer_id IN (SELECT id FROM organizer WHERE oid = :oid))

-- submitted registration expires 8 days from payment creation at midnight
-- name: update-submitted-registrations-to-expired<!
UPDATE registration
   SET state = 'EXPIRED',
    modified = current_timestamp
 WHERE state = 'SUBMITTED'
   AND id IN (SELECT r.id FROM payment p
               INNER JOIN registration r ON r.id = p.registration_id
               WHERE p.state = 'UNPAID'
                 AND ((r.kind = 'ADMISSION' AND ts_older_than(p.created, interval '9 days'))
                      OR (r.kind = 'POST_ADMISSION' AND ts_older_than(p.created, interval '2 days'))))
RETURNING id as updated;

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
  pa.state,
  pa.registration_id,
  pa.amount,
  pa.reference_number,
  pa.order_number,
  pa.external_payment_id,
  pa.payment_method,
  pa.payed_at
 FROM payment pa
 INNER JOIN registration re ON re.id = pa.registration_id
 INNER JOIN exam_session es ON es.id = re.exam_session_id
 WHERE registration_id = :registration_id
 AND es.organizer_id IN (SELECT id FROM organizer o WHERE oid = COALESCE(:oid, o.oid));

-- name: select-participant-by-external-id
SELECT id, external_user_id, email
FROM participant
WHERE external_user_id = :external_user_id;

-- name: select-participant-by-id
SELECT id, external_user_id, email
FROM participant
WHERE id = :id;

-- name: select-participant-data-by-order-number
SELECT par.email,
       pay.lang,
       es.language_code,
       es.level_code,
       esl.name,
       esl.street_address,
       esl.zip,
       esl.post_office,
       ed.exam_date
FROM participant par
INNER JOIN registration re ON re.participant_id = par.id
INNER JOIN payment pay ON re.id = pay.registration_id
INNER JOIN exam_session es ON es.id = re.exam_session_id
INNER JOIN exam_session_location esl ON esl.exam_session_id = es.id
INNER JOIN exam_date ed ON ed.id = es.exam_date_id
WHERE pay.order_number = :order_number
  AND esl.lang = pay.lang;

-- name: select-payment-by-order-number
SELECT
  p.state,
  p.registration_id,
  p.amount,
  p.lang,
  p.reference_number,
  p.order_number,
  p.external_payment_id,
  p.payment_method,
  p.payed_at,
  re.exam_session_id
FROM payment p
INNER JOIN registration re ON re.id = p.registration_id
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
                    WHERE exam_session_id = :exam_session_id)
ON CONFLICT DO NOTHING;

-- Syncronization is done during registration period and
-- failed sync attempts will be retried for given period
-- after registration has ended.

-- name: select-exam-sessions-to-be-synced
SELECT es.id as exam_session_id, pss.created
FROM exam_session es
INNER JOIN exam_date ed ON es.exam_date_id = ed.id
LEFT JOIN participant_sync_status pss ON pss.exam_session_id = es.id
WHERE ((ed.registration_end_date + interval '1 day') >= current_date
  OR (ed.post_admission_end_date + interval '1 day') >= current_date
  OR ((ed.registration_end_date + :duration::interval) >= current_date
      AND pss.failed_at IS NOT NULL
      AND (pss.success_at IS NULL OR pss.failed_at > pss.success_at)))
AND (ed.registration_start_date <= current_date OR es.post_admission_start_date <= current_date)
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

-- name: select-completed-exam-session-participants
SELECT form, person_oid
FROM registration
WHERE exam_session_id = :id
AND state = 'COMPLETED';

-- name: select-exam-session-participants
SELECT
  r.form,
  r.state,
  r.id as registration_id,
  r.kind,
  r.original_exam_session_id,
  pa.order_number,
  pa.created
FROM exam_session es
INNER JOIN registration r ON es.id = r.exam_session_id
LEFT JOIN payment pa ON pa.registration_id = r.id
WHERE es.id = :id
AND es.organizer_id IN (SELECT id FROM organizer WHERE oid = :oid)
AND (r.state IN ('COMPLETED', 'SUBMITTED', 'CANCELLED', 'PAID_AND_CANCELLED')
    OR (r.state = 'EXPIRED' AND EXISTS (SELECT id
                                        FROM payment
                                        WHERE registration_id = r.id)))
ORDER BY r.created ASC;

--name: update-registration-status-to-cancelled!
UPDATE registration
SET state =
  CASE WHEN state = 'SUBMITTED'::registration_state THEN 'CANCELLED'::registration_state
       ELSE 'PAID_AND_CANCELLED'::registration_state
  END
WHERE id = :id
AND exam_session_id IN (SELECT id
                        FROM exam_session
                        WHERE organizer_id IN
                          (SELECT id
                            FROM organizer
                            WHERE oid = :oid));

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
  merchant_secret,
  test_mode
FROM payment_config
WHERE organizer_id = :organizer_id;

-- name: select-payment-config-by-order-number
SELECT
  pc.organizer_id,
  pc.merchant_id,
  pc.merchant_secret,
  pc.test_mode
FROM payment_config pc
INNER JOIN organizer o ON pc.organizer_id = o.id
INNER JOIN exam_session es ON o.id = es.organizer_id
INNER JOIN registration re ON es.id = re.exam_session_id
INNER JOIN payment p ON re.id = p.registration_id
WHERE p.order_number = :order_number;

-- name: update-payment-config!
UPDATE payment_config
SET merchant_id = :merchant_id,
    merchant_secret = :merchant_secret
WHERE organizer_id =
  (SELECT id FROM organizer
    WHERE oid = :oid
    AND deleted_at IS NULL);

-- name: delete-payment-config!
DELETE FROM payment_config
WHERE organizer_id =
  (SELECT id FROM organizer
    WHERE oid = :oid
    AND deleted_at IS NULL);

-- name: select-exam-dates
SELECT ed.id, ed.exam_date, ed.registration_start_date, ed.registration_end_date, ed.post_admission_end_date,
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

-- name: insert-exam-session-queue!
INSERT INTO exam_session_queue (
  email,
  lang,
  exam_session_id
) VALUES (
  :email,
  :lang,
  (SELECT id
    FROM exam_session
    WHERE id = :exam_session_id
    GROUP BY id
    HAVING (SELECT COUNT(1) FROM exam_session_queue WHERE exam_session_id = :exam_session_id) < 50)
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
SELECT post_admission_start_date, post_admission_active, post_admission_quota
  FROM exam_session
 WHERE id = :exam_session_id;

--name: activate-post-admission!
UPDATE exam_session
   SET post_admission_active = :post_admission_active
 WHERE id = :exam_session_id;

--name: update-post-admission-details!
UPDATE exam_session
   SET post_admission_start_date = :post_admission_start_date,
       post_admission_quota = :post_admission_quota
   WHERE post_admission_active = FALSE AND id = :exam_session_id;

--name: update-post-admission-end-date!
UPDATE exam_date
   SET post_admission_end_date = :post_admission_end_date
 WHERE id = :exam_date_id;

--name: delete-post-admission-end-date!
UPDATE exam_date
   SET post_admission_end_date = NULL
 WHERE id = :exam_date_id;
