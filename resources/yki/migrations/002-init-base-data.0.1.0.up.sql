INSERT INTO language(code) VALUES ('fin');
--;;
INSERT INTO language(code) VALUES ('swe');
--;;
INSERT INTO language(code) VALUES ('eng');
--;;
INSERT INTO language(code) VALUES ('spa');
--;;
INSERT INTO language(code) VALUES ('ita');
--;;
INSERT INTO language(code) VALUES ('fra');
--;;
INSERT INTO language(code) VALUES ('sme');
--;;
INSERT INTO language(code) VALUES ('deu');
--;;
INSERT INTO language(code) VALUES ('rus');
--;;
INSERT INTO exam_level(code) VALUES ('PERUS');
--;;
INSERT INTO exam_level(code) VALUES ('KESKI');
--;;
INSERT INTO exam_level(code) VALUES ('YLIN');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2018-01-27', '2017-12-01', '2017-12-08');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-01-27'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2018-10-27', '2018-09-03', '2018-09-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-10-27'), 'eng');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2018-11-10', '2018-09-03', '2018-09-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-11-10'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2018-11-17', '2018-09-03', '2018-09-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-11-17'), 'spa');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-11-17'), 'sme');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2018-11-17'), 'deu');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-01-26', '2018-12-03', '2018-12-14');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-01-26'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-03-23', '2019-02-01', '2019-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-03-23'), 'eng');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-03-23'), 'ita');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-03-23'), 'rus');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-04-06', '2019-02-01', '2019-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-04-06'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-04-13', '2019-02-01', '2019-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-04-13'), 'fra');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-04-13'), 'swe');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-04-13'), 'sme');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-05-18', '2019-04-15', '2019-04-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-05-18'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-08-31', '2019-06-03', '2019-06-14');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-08-31'), 'fin');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-08-31'), 'swe');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-10-05', '2019-08-19', '2019-08-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-10-05'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-10-26', '2019-09-02', '2019-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-10-26'), 'eng');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-11-09', '2019-09-02', '2019-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-11-09'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2019-11-16', '2019-09-02', '2019-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-11-16'), 'spa');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-11-16'), 'swe');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2019-11-16'), 'deu');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-01-25', '2019-12-02', '2019-12-13');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-01-25'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-03-14', '2020-02-02', '2020-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-03-14'), 'eng');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-03-14'), 'ita');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-03-14'), 'rus');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-03-28', '2020-02-03', '2020-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-03-28'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-04-04', '2020-02-03', '2020-02-28');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-04-04'), 'fra');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-04-04'), 'swe');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-04-04'), 'sme');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-05-23', '2020-04-20', '2020-04-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-05-23'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-08-29', '2020-06-01', '2020-06-12');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-08-29'), 'swe');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-08-29'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-10-03', '2020-08-17', '2020-08-31');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-10-03'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-10-23', '2020-09-01', '2020-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-10-23'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-10-24', '2020-09-01', '2020-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-10-24'), 'eng');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-11-07', '2020-09-01', '2020-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-11-07'), 'fin');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2020-11-14', '2020-09-01', '2020-09-30');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-11-14'), 'spa');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-11-14'), 'swe');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2020-11-14'), 'deu');
--;;
INSERT INTO exam_date(exam_date, registration_start_date, registration_end_date) VALUES ('2021-01-30', '2020-12-01', '2020-12-11');
--;;
INSERT INTO exam_date_language(exam_date_id, language_code) VALUES ((SELECT id FROM exam_date WHERE exam_date = '2021-01-30'), 'fin');
