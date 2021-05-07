ALTER TABLE exam_session
ADD COLUMN IF NOT EXISTS post_admission_activated_at DATE DEFAULT NULL;

CREATE OR REPLACE FUNCTION exam_session_post_registration_open(exam_date_id bigint,
                                                               at_point_in_time timestamptz DEFAULT now()) RETURNS SETOF boolean AS $$
BEGIN
    RETURN QUERY SELECT EXISTS (
                     SELECT es.id
                       FROM exam_session es
                 INNER JOIN exam_date ed ON es.exam_date_id = ed.id
                      WHERE es.id = exam_session_post_registration_open.exam_date_id
                        AND es.post_admission_active = TRUE
                        AND within_dt_range(at_point_in_time, ed.post_admission_start_date, ed.post_admission_end_date)
                 ) as exists;
END;
$$ LANGUAGE plpgsql;
