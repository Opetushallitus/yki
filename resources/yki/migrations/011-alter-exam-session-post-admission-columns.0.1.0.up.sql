DO $$
BEGIN
  IF EXISTS(SELECT *
    FROM information_schema.columns
    WHERE table_name='exam_session' and column_name='post_admission_start_date')
  THEN
      ALTER TABLE "public"."exam_session" RENAME COLUMN "post_admission_start_date" TO "post_admission_activated_at";
  END IF;
END $$;

-- Is post registration open for exam session?
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
