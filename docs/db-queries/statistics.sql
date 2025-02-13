WITH times AS (SELECT es.exam_date_id,
                      es.language_code,
                      es.level_code,
                      es.organizer_id,
                      to_char(AVG(r.started_at::time), 'HH24:MI:SS')                                         AS ilmo_alku_AVG,
                      to_char(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY r.started_at::time),
                              'HH24:MI:SS')                                                                  AS ilmo_alku_MEDIAN,
                      to_char(AVG(r.modified::time), 'HH24:MI:SS')                                           AS ilmo_loppu_AVG,
                      to_char(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY r.modified::time),
                              'HH24:MI:SS')                                                                  AS ilmo_loppu_MEDIAN
               FROM exam_session es
                        LEFT JOIN registration r ON r.exam_session_id = es.id AND r.state = 'COMPLETED'
               GROUP BY es.exam_date_id, es.language_code, es.level_code, es.organizer_id),
     counts AS (SELECT es.exam_date_id,
                       es.language_code,
                       es.level_code,
                       es.organizer_id,
                       count(distinct r.id)        AS reg_count,
                       count(distinct r_moved.id)  AS moved_count,
                       count(distinct r_cancel.id) AS cancel_count,
                       count(distinct r_pa.id)     AS pa_count,
                       count(distinct q.id)        AS queue_count,
                       count(distinct eo.id)       AS evaluation_count
                FROM exam_session es
                         LEFT JOIN registration r ON r.exam_session_id = es.id AND r.state = 'COMPLETED'
                         LEFT JOIN registration r_moved
                                   ON r_moved.exam_session_id = es.id AND r_moved.is_transfered = true
                         LEFT JOIN registration r_cancel
                                   ON r_cancel.exam_session_id = es.id AND r_cancel.state = 'PAID_AND_CANCELLED'
                         LEFT JOIN registration r_pa ON r_pa.exam_session_id = es.id AND r_pa.kind = 'POST_ADMISSION'
                         LEFT JOIN exam_date ed ON es.exam_date_id = ed.id
                         LEFT JOIN evaluation e ON e.exam_date_id = ed.id
                         LEFT JOIN evaluation_order eo ON e.id = eo.evaluation_id
                         LEFT JOIN exam_session_queue q ON es.id = q.exam_session_id
                GROUP BY es.exam_date_id, es.language_code, es.level_code, es.organizer_id)

SELECT ed.exam_date,
       es.language_code,
       es.level_code,
       o.oid,
       (SELECT sum(s.max_participants)
        FROM exam_session s
        WHERE s.exam_date_id = ed.id
          AND s.organizer_id = o.id
          AND s.language_code = es.language_code
          AND s.level_code = es.level_code) AS paikkoja,
       ed.post_admission_enabled,
       --DEBUG: string_agg(cast(es.id as varchar), ','),
       max(counts.reg_count)                AS ilmoittautuneet,
       max(counts.queue_count)              AS jonoon_ilmoittautuneet,
       max(counts.moved_count)              AS siirrettyjä,
       max(counts.cancel_count)             AS peruutuksia,
       max(counts.pa_count)                 AS jälkiilmoittautumisia,
       max(counts.evaluation_count)         AS tarkistusarviontipyynnöt,
       max(times.ilmo_alku_AVG)             AS ilmo_alku_avg,
       max(times.ilmo_loppu_AVG)            AS ilmo_loppu_avg,
       max(times.ilmo_alku_MEDIAN)          AS ilmo_alku_median,
       max(times.ilmo_loppu_MEDIAN)         AS ilmo_loppu_median

FROM exam_session es
         JOIN exam_date ed ON es.exam_date_id = ed.id
         JOIN organizer o ON es.organizer_id = o.id
         LEFT JOIN counts ON counts.exam_date_id = es.exam_date_id AND counts.language_code = es.language_code AND
                             counts.level_code = es.level_code AND counts.organizer_id = es.organizer_id
         LEFT JOIN times ON times.exam_date_id = es.exam_date_id AND times.language_code = es.language_code AND
                            times.level_code = es.level_code AND times.organizer_id = es.organizer_id
GROUP BY ed.exam_date, es.language_code, es.level_code, o.oid, ed.post_admission_enabled, ed.id, o.id
ORDER BY exam_date, oid, language_code, level_code;
