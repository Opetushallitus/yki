CREATE OR REPLACE FUNCTION select_registration_modification_dl(registration_id bigint) RETURNS TIMESTAMP WITH TIME ZONE
    LANGUAGE PLPGSQL
AS
$$
BEGIN
    RETURN date_trunc('day',
        (SELECT ed.exam_date FROM registration r
         INNER JOIN exam_session es ON r.exam_session_id = es.id
         INNER JOIN exam_date ed ON es.exam_date_id = ed.id
        WHERE r.id=registration_id)
        AT TIME ZONE 'Europe/Helsinki') AT TIME ZONE 'Europe/Helsinki';
END;
$$;

CREATE OR REPLACE FUNCTION is_transferable(registration_id bigint) RETURNS SETOF BOOLEAN
    LANGUAGE PLPGSQL
AS
$$
BEGIN
    RETURN QUERY SELECT EXISTS
                            (SELECT r.id
                             FROM registration r
                             WHERE r.id = registration_id
                               AND r.is_transfered = FALSE
                               AND r.state = 'COMPLETED'
                               AND current_timestamp < select_registration_modification_dl(r.id));
END;
$$;
