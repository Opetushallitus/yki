# DB queries for random occasions

## Payment returns (in case of mass cancellations e.g. corona pandemic)

Fetch payment info for all registrations for all organizers

``` sql
SELECT
 organizer.oid,
 organizer.contact_email,
 registration.form,
 registration.person_oid,
 registration.state,
 payment.state,
 payment.amount,
 payment.order_number,
 payment.external_payment_id,
 payment.payed_at,
 payment.payment_method,
 exam_date.exam_date,
 exam_session.language_code,
 exam_session.level_code
FROM registration
 JOIN payment ON registration.id = payment.registration_id
 JOIN exam_session ON registration.exam_session_id = exam_session.id
 JOIN organizer ON exam_session.organizer_id = organizer.id
 JOIN exam_date ON exam_session.exam_date_id = exam_date.id
WHERE
 registration.state IN ('COMPLETED', 'PAID_AND_CANCELLED') AND
 payment.created >= '2020-01-01';
```

## Exam dates

Add exam date with registration period

```sql
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-08-22', '2020-06-01', '2020-06-12');
```

Add languages for given exam date

```sql
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-08-22'), 'eng');
```
