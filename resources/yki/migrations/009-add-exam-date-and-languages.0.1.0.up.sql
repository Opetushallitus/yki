INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date)
     VALUES ('2020-08-22', '2020-06-01', '2020-06-12')
ON CONFLICT (exam_date, registration_start_date, registration_end_date)
DO NOTHING;
--;;
INSERT INTO exam_date_language(exam_date_id, language_code)
     VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-08-22'), 'eng')
ON CONFLICT (exam_date_id, language_code)
DO NOTHING;
--;;
INSERT INTO exam_date_language(exam_date_id, language_code)
     VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-08-22'), 'fra')
ON CONFLICT (exam_date_id, language_code)
DO NOTHING;
