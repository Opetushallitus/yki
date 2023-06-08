-- Insert organizers with information, which exams they provide and with which levels
TRUNCATE organizer CASCADE;
TRUNCATE contact CASCADE;
TRUNCATE exam_language CASCADE;

-- Organizer 1.2.246.562.10.73550603424, has id: 14 in untuva yki db

INSERT INTO organizer(oid, agreement_start_date, agreement_end_date)
VALUES(
  '1.2.246.562.10.73550603424',
  NOW(),
  NOW() + INTERVAL '3 YEARS'
);

INSERT INTO contact(organizer_id, name, email, phone_number)
VALUES(
  (SELECT(MAX(id)) FROM organizer),
  'Pääkaupunkiseudun koulutus',
  'pkseudunkoulutus@yki.invalid',
  '+35840111222333444'
);

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
  lang_code[i],
  'PERUS',
  (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 9) AS i,
       (SELECT ('{fin, swe, eng, spa, ita, fra, sme, deu, rus}')::text[] AS lang_code) AS lang_codes;

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
    lang_code[i],
    'KESKI',
    (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 5) AS i,
     (SELECT ('{fin, swe, eng, spa, ita}')::text[] AS lang_code) AS lang_codes;

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
    lang_code[i],
    'YLIN',
    (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 2) AS i,
     (SELECT ('{fin, swe}')::text[] AS lang_code) AS lang_codes;

-- Organizer 1.2.246.562.10.28646781493, has id: 4 in untuva yki db

INSERT INTO organizer(oid, agreement_start_date, agreement_end_date)
VALUES(
  '1.2.246.562.10.28646781493',
  NOW() - INTERVAL '1 YEARS',
  NOW() + INTERVAL '4 YEARS'
);

INSERT INTO contact(organizer_id, name, email, phone_number)
VALUES(
(SELECT(MAX(id)) FROM organizer),
  'Jyväskylän koulutusyhtymä',
  'jyvaskyla.koulutusyhtyma@yki.invalid',
  '+35840222333444555'
);

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
  lang_code[i],
  'PERUS',
  (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 4) AS i,
     (SELECT ('{eng, spa, ita, fra}')::text[] AS lang_code) AS lang_codes;

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
  lang_code[i],
  'KESKI',
  (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 3) AS i,
     (SELECT ('{eng, spa, ita}')::text[] AS lang_code) AS lang_codes;

INSERT INTO exam_language(language_code, level_code, organizer_id)
SELECT
  lang_code[i],
  'YLIN',
  (SELECT(MAX(id)) FROM organizer)
FROM generate_series(1, 3) AS i,
     (SELECT ('{eng, spa, deu}')::text[] AS lang_code) AS lang_codes;


-- Insert some exam dates, exam sessions, registrations and evaluations
TRUNCATE exam_date CASCADE;
TRUNCATE participant CASCADE;
TRUNCATE registration CASCADE;
TRUNCATE evaluation CASCADE;

-- Exam date 1 (past exam date)

INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date)
VALUES(
  NOW() - INTERVAL '7 DAYS',
  NOW() - INTERVAL '30 DAYS',
  NOW() - INTERVAL '20 DAYS'
);

INSERT INTO exam_date_language(exam_date_id, language_code, level_code)
SELECT
  (SELECT(MAX(id)) FROM exam_date),
  'fin',
  level_code[i]
FROM generate_series(1, 3) AS i,
     (SELECT ('{PERUS, KESKI, YLIN}')::text[] AS level_code) AS level_codes;


INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
  (SELECT(MAX(id) - 1) FROM organizer),
  'fin',
  'PERUS',
  (SELECT(MAX(id)) FROM exam_date),
  20
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Vantaan koulutuskeskus',
  'Tikkurilankuja 33',
  'Vantaa',
  '03210',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  'Auditorio A1'
);

INSERT INTO evaluation(exam_date_id, exam_date_language_id, evaluation_start_date, evaluation_end_date)
VALUES(
  (SELECT(MAX(id)) FROM exam_date),
  (SELECT(MAX(id)) FROM exam_date_language),
  NOW(),
  NOW() + INTERVAL '14 DAYS'
);

-- Exam date 2 (post admission ongoing)

INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date, post_admission_enabled, post_admission_start_date, post_admission_end_date)
VALUES(
  NOW() + INTERVAL '7 DAYS',
  NOW() - INTERVAL '14 DAYS',
  NOW() - INTERVAL '7 DAYS',
  TRUE,
  NOW(),
  NOW() + INTERVAL '3 DAYS'
);

INSERT INTO exam_date_language(exam_date_id, language_code, level_code)
SELECT
    (SELECT(MAX(id)) FROM exam_date),
    'swe',
    level_code[i]
FROM generate_series(1, 2) AS i,
     (SELECT ('{PERUS, KESKI}')::text[] AS level_code) AS level_codes;


INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants, post_admission_start_date, post_admission_active, post_admission_quota, post_admission_activated_at)
VALUES(
  (SELECT(MAX(id) - 1) FROM organizer),
  'swe',
  'KESKI',
  (SELECT(MAX(id)) FROM exam_date),
  10,
  NOW(),
  TRUE,
  5,
  NOW()
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Vantaan koulutuskeskus',
  'Tikkurilankuja 33',
  'Vantaa',
  '03210',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  'Auditorio A1'
);

-- Participant has oid of a person inserted to onr untuva db for testing otr
INSERT INTO participant(external_user_id) VALUES('1.2.246.562.24.31234500001');

INSERT INTO registration(state, exam_session_id, participant_id, started_at, form, form_version, person_oid, kind)
VALUES(
  'COMPLETED',
  (SELECT(MAX(id)) FROM exam_session),
  (SELECT(MAX(id)) FROM participant),
  NOW(),
  '{"ssn": "131000A001F", "zip": "20100", "email": "skibadiskabadi@test.invalid", "gender": "1", "birthdate": "2000-10-13", "exam_lang": "fi", "last_name": "Anka", "first_name": "Kalle", "post_office": "TURKU", "phone_number": "+358201122334", "nationalities": ["528"], "street_address": "Aku Ankan katu 313", "certificate_lang": "en", "nationality_desc": "Alankomaat"}',
  1,
  '1.2.246.562.24.31234500001',
  'POST_ADMISSION'
);

INSERT INTO evaluation(exam_date_id, exam_date_language_id, evaluation_start_date, evaluation_end_date)
VALUES(
  (SELECT(MAX(id)) FROM exam_date),
  (SELECT(MAX(id)) FROM exam_date_language),
  NOW() + INTERVAL '8 DAYS',
  NOW() + INTERVAL '30 DAYS'
);

-- Exam date 3 (registration ongoing)

INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date)
VALUES(
  NOW() + INTERVAL '21 DAYS',
  NOW() - INTERVAL '1',
  NOW() + INTERVAL '7 DAYS'
);

INSERT INTO exam_date_language(exam_date_id, language_code, level_code)
SELECT
    (SELECT(MAX(id)) FROM exam_date),
    language_code[i],
    'PERUS'
FROM generate_series(1, 4) AS i,
     (SELECT ('{fin, swe, eng, deu}')::text[] AS language_code) AS language_codes;

INSERT INTO exam_date_language(exam_date_id, language_code, level_code)
SELECT
    (SELECT(MAX(id)) FROM exam_date),
    language_code[i],
    'YLIN'
FROM generate_series(1, 4) AS i,
     (SELECT ('{fin, swe, eng, deu}')::text[] AS language_code) AS language_codes;


INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
  (SELECT(MAX(id) - 1) FROM organizer),
  'fin',
  'PERUS',
  (SELECT(MAX(id)) FROM exam_date),
  20
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Vantaan koulutuskeskus',
  'Tikkurilankuja 33',
  'Vantaa',
  '03210',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  'Auditorio A1'
);

-- Participant has oid of a person inserted to onr untuva db for testing otr
INSERT INTO participant(external_user_id) VALUES('1.2.246.562.24.31234500002');

INSERT INTO registration(state, exam_session_id, participant_id, started_at, form, form_version, person_oid, kind)
VALUES(
  'COMPLETED',
  (SELECT(MAX(id)) FROM exam_session),
  (SELECT(MAX(id)) FROM participant),
  NOW(),
  '{"ssn": "131000A001F", "zip": "20100", "email": "skibadiskabadi@test.invalid", "gender": "1", "birthdate": "2000-10-13", "exam_lang": "fi", "last_name": "Anka", "first_name": "Kalle", "post_office": "TURKU", "phone_number": "+358201122334", "nationalities": ["528"], "street_address": "Aku Ankan katu 313", "certificate_lang": "en", "nationality_desc": "Alankomaat"}',
  1,
  '1.2.246.562.24.31234500002',
  'ADMISSION'
);

-- Participant has oid of a person inserted to onr untuva db for testing otr
INSERT INTO participant(external_user_id) VALUES('1.2.246.562.24.31234500003');

INSERT INTO registration(state, exam_session_id, participant_id, started_at, form, form_version, person_oid, kind)
VALUES(
  'COMPLETED',
  (SELECT(MAX(id)) FROM exam_session),
  (SELECT(MAX(id)) FROM participant),
  NOW(),
  '{"ssn": "", "zip": "00900", "email": "zipzap@test.invalid", "gender": "1", "birthdate": "1999-09-09", "exam_lang": "fi", "last_name": "Aurelius", "first_name": "Marcus", "post_office": "HELSINKI", "phone_number": "+358205556666", "nationalities": ["496"], "street_address": "Stoan aukio", "certificate_lang": "en", "nationality_desc": "Mongolia"}',
  1,
  '1.2.246.562.24.31234500003',
  'ADMISSION'
);

INSERT INTO registration(state, exam_session_id, participant_id, started_at, person_oid, kind)
VALUES(
  'EXPIRED',
  (SELECT(MAX(id)) FROM exam_session),
  (SELECT(id) FROM participant WHERE external_user_id = '1.2.246.562.24.31234500001'),
  NOW(),
  '1.2.246.562.24.31234500001',
  'ADMISSION'
);

INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
(SELECT(MAX(id) - 1) FROM organizer),
  'eng',
  'PERUS',
  (SELECT(MAX(id)) FROM exam_date),
  13
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Töölönrannan aikuisopisto',
  'TÖÖLÖNKATU 55',
  'HELSINKI',
  '00500',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  ''
);

INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
(SELECT(MAX(id) - 1) FROM organizer),
  'fin',
  'YLIN',
  (SELECT(MAX(id)) FROM exam_date),
  6
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Pääkaupunkiseudun kuntayhtymä',
  'Finnoontie 88',
  'Espoo',
  '02100',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  ''
);

INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
  (SELECT(MAX(id) - 1) FROM organizer),
  'swe',
  'YLIN',
  (SELECT(MAX(id)) FROM exam_date),
  5
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Helsingin kaupunki',
  'Erottajankatu 99',
  'Helsinki',
  '00500',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  ''
);

-- Insert contact "Pääkaupunkiseudun koulutus" for exam sessions inserted above

INSERT INTO exam_session_contact(exam_session_id, contact_id)
SELECT es.id, (SELECT(MAX(id) - 1) FROM contact)
FROM exam_session es;


INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
  (SELECT(MAX(id)) FROM organizer),
  'eng',
  'PERUS',
  (SELECT(MAX(id)) FROM exam_date),
  9
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Jyväskylän ala-aste',
  'Kilpisenkatu 22',
  'Jyväskylä',
  '40100',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  'Luokkahuone A4'
);

INSERT INTO exam_session(organizer_id, language_code, level_code, exam_date_id, max_participants)
VALUES(
  (SELECT(MAX(id)) FROM organizer),
  'deu',
  'YLIN',
  (SELECT(MAX(id)) FROM exam_date),
  3
);

INSERT INTO exam_session_location(name, street_address, post_office, zip, lang, exam_session_id, other_location_info)
VALUES(
  'Jyväskylän ala-aste',
  'KILPISENKATU 22',
  'JYVÄSKYLÄ',
  '40100',
  'fi',
  (SELECT(MAX(id)) FROM exam_session),
  'Luokkahuone B7'
);

-- Insert contact "Jyväskylän koulutusyhtymä" for the two exam sessions inserted above

INSERT INTO exam_session_contact(exam_session_id, contact_id)
SELECT es.id, (SELECT(MAX(id)) FROM contact)
FROM exam_session es
WHERE es.id NOT IN (SELECT exam_session_id FROM exam_session_contact);


-- Exam date 4 (future exam date)

INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date)
VALUES(
  NOW() + INTERVAL '100 DAYS',
  NOW() + INTERVAL '60 DAYS',
  NOW() + INTERVAL '80 DAYS'
);

INSERT INTO exam_date_language(exam_date_id, language_code, level_code)
SELECT
    (SELECT(MAX(id)) FROM exam_date),
    language_code[i],
    'KESKI'
FROM generate_series(1, 3) AS i,
     (SELECT ('{eng, spa, ita}')::text[] AS language_code) AS language_codes;


-- Insert some quarantines
TRUNCATE quarantine CASCADE;

INSERT INTO quarantine(language_code, start_date, end_date, birthdate, first_name, last_name, diary_number, ssn, phone_number)
VALUES(
  'eng',
  NOW() - INTERVAL '11 MONTHS',
  NOW() + INTERVAL '1 MONTH',
  '2000-01-01',
  'Teppo',
  'Testinen',
  'dokumentti-111',
  '010100A123B',
  '+35850111000222'
);

-- Has related registration based on birthdate
INSERT INTO quarantine(language_code, start_date, end_date, birthdate, first_name, last_name, diary_number, email, phone_number)
VALUES(
  'fin',
  NOW() - INTERVAL '4 MONTHS',
  NOW() + INTERVAL '2 MONTHS',
  '1999-09-09',
  'Markus',
  'Aurelius',
  'dokumentti-222',
  'markus.aurelius@test.invalid',
  '+358205556666'
);
