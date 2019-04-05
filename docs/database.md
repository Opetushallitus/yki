---
title: Database Documentation

Generated with [make-postgres-markdown](https://github.com/cazzer/make-postgres-markdown).
---

# Tables

## attachment_metadata

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
external_id |  | text |  | NOT NULL |
organizer_id |  | bigint | nextval('attachment_metadata_organizer_id_seq'::regclass) | NOT NULL, [organizer_id](#organizer) |
type |  | text |  |  |
deleted_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## cas_ticketstore

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
ticket |  | text |  | NOT NULL |
logged_in |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_date

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_date_id_seq'::regclass) | NOT NULL |
exam_date |  | date |  | NOT NULL |
registration_start_date |  | date |  | NOT NULL |
registration_end_date |  | date |  | NOT NULL |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_date_language

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_date_language_id_seq'::regclass) | NOT NULL |
exam_date_id |  | bigint | nextval('exam_date_language_exam_date_id_seq'::regclass) | NOT NULL, [exam_date_id](#exam_date) |
language_code |  | character |  | NOT NULL, [language_code](#language) |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_language

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_language_id_seq'::regclass) | NOT NULL |
language_code |  | character |  | NOT NULL, [language_code](#language) |
level_code |  | text |  | NOT NULL, [level_code](#exam_level) |
organizer_id |  | bigint | nextval('exam_language_organizer_id_seq'::regclass) | NOT NULL, [organizer_id](#organizer) |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_level

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
code |  | text |  | NOT NULL |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_session

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_session_id_seq'::regclass) | NOT NULL |
organizer_id |  | bigint | nextval('exam_session_organizer_id_seq'::regclass) | NOT NULL, [organizer_id](#organizer) |
language_code |  | character |  | NOT NULL, [language_code](#language) |
level_code |  | text |  | NOT NULL, [level_code](#exam_level) |
exam_date_id |  | bigint | nextval('exam_session_exam_date_id_seq'::regclass) | NOT NULL, [exam_date_id](#exam_date) |
max_participants |  | integer |  | NOT NULL |
office_oid |  | text |  |  |
published_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_session_location

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_session_location_id_seq'::regclass) | NOT NULL |
name |  | text |  | NOT NULL |
street_address |  | text |  | NOT NULL |
post_office |  | text |  | NOT NULL |
zip |  | text |  | NOT NULL |
other_location_info |  | text |  |  |
extra_information |  | text |  |  |
lang |  | character |  | NOT NULL |
exam_session_id |  | bigint |  | NOT NULL, [exam_session_id](#exam_session) |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## exam_session_queue

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('exam_session_queue_id_seq'::regclass) | NOT NULL |
email |  | text |  | NOT NULL |
lang |  | character |  | NOT NULL |
exam_session_id |  | bigint |  | NOT NULL, [exam_session_id](#exam_session) |
last_notified_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## language

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
code |  | character |  | NOT NULL |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## login_link

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('login_link_id_seq'::regclass) | NOT NULL |
code |  | text |  | NOT NULL |
participant_id |  | bigint | nextval('login_link_participant_id_seq'::regclass) | NOT NULL, [participant_id](#participant) |
exam_session_id |  | bigint |  | [exam_session_id](#exam_session) |
registration_id |  | bigint |  | [registration_id](#registration) |
type |  | user-defined |  | NOT NULL | LOGIN, REGISTRATION, PAYMENT
expired_link_redirect |  | text |  | NOT NULL |
success_redirect |  | text |  | NOT NULL |
expires_at |  | timestamp with time zone |  | NOT NULL |
user_data |  | jsonb |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## organizer

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('organizer_id_seq'::regclass) | NOT NULL |
oid |  | text |  | NOT NULL |
agreement_start_date |  | date |  | NOT NULL |
agreement_end_date |  | date |  | NOT NULL |
contact_name |  | text |  |  |
contact_email |  | text |  |  |
contact_phone_number |  | text |  |  |
extra |  | text |  |  |
deleted_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## participant

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('participant_id_seq'::regclass) | NOT NULL |
external_user_id |  | text |  | NOT NULL |
email |  | text |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## participant_sync_status

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('participant_sync_status_id_seq'::regclass) | NOT NULL |
exam_session_id |  | bigint | nextval('participant_sync_status_exam_session_id_seq'::regclass) | NOT NULL, [exam_session_id](#exam_session) |
success_at |  | timestamp with time zone |  |  |
failed_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## payment

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('payment_id_seq'::regclass) | NOT NULL |
state |  | user-defined |  | NOT NULL | PAID, UNPAID, ERROR
registration_id |  | bigint | nextval('payment_registration_id_seq'::regclass) | NOT NULL, [registration_id](#registration) |
amount |  | numeric |  | NOT NULL |
lang |  | character |  | NOT NULL |
reference_number |  | numeric |  |  |
order_number |  | text |  | NOT NULL |
external_payment_id |  | text |  |  |
payment_method |  | text |  |  |
payed_at |  | timestamp with time zone |  |  |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

## payment_config

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('payment_config_id_seq'::regclass) | NOT NULL |
organizer_id |  | bigint | nextval('payment_config_organizer_id_seq'::regclass) | NOT NULL, [organizer_id](#organizer) |
merchant_id |  | integer |  |  |
merchant_secret |  | text |  |  |
test_mode |  | boolean | false |  |

## pgqueues

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('pgqueues_id_seq'::regclass) | NOT NULL |
name |  | text |  | NOT NULL |
priority |  | integer | 100 | NOT NULL |
data |  | bytea |  |  |
deleted |  | boolean | false | NOT NULL |

## ragtime_migrations

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | character varying |  |  |
created_at |  | character varying |  |  |

## registration

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
id |  | bigint | nextval('registration_id_seq'::regclass) | NOT NULL |
state |  | user-defined |  | NOT NULL | COMPLETED, SUBMITTED, STARTED, EXPIRED, CANCELLED, PAID_AND_CANCELLED
exam_session_id |  | bigint |  | NOT NULL, [exam_session_id](#exam_session) |
participant_id |  | bigint |  | NOT NULL, [participant_id](#participant) |
started_at |  | timestamp with time zone |  |  |
form |  | jsonb |  |  |
form_version |  | integer |  |  |
person_oid |  | text |  |  |
original_exam_session_id |  | bigint |  | [original_exam_session_id](#exam_session) |
created |  | timestamp with time zone | CURRENT_TIMESTAMP |  |
modified |  | timestamp with time zone | CURRENT_TIMESTAMP |  |

### Triggers

name | timing | orientation | manipulation | statement
--- | --- | --- | --- | ---
participant_limit_trigger | BEFORE | ROW | INSERT | EXECUTE PROCEDURE <a href="#error_if_exceeds_participant_limit">error_if_exceeds_participant_limit</a>()

## task_lock

column | comment | type | default | constraints | values
--- | --- | --- | --- | --- | ---
task |  | text |  | NOT NULL |
last_executed |  | timestamp with time zone |  | NOT NULL |
worker_id |  | text |  |  |

# Views

# Roles

name | super user | inherits | create role | create database | can login | bypass RLS | connection limit | configuration | roles granted
--- | --- | --- | --- | --- | --- | --- | --- | --- | ---
pg_signal_backend | false | true | false | false | false | false | -1 |  |
postgres | true | true | true | true | true | true | -1 |  |
pg_read_all_stats | false | true | false | false | false | false | -1 |  | {pg_monitor}
pg_monitor | false | true | false | false | false | false | -1 |  |
pg_read_all_settings | false | true | false | false | false | false | -1 |  | {pg_monitor}
pg_stat_scan_tables | false | true | false | false | false | false | -1 |  | {pg_monitor}
admin | true | true | false | false | true | false | -1 |  |

# Functions

## at_midnight

return type | volatility
--- | ---
timestamptz | v

```sql

  SELECT (date_trunc('day', $1 AT TIME ZONE 'Europe/Helsinki') + interval '1 day') AT TIME ZONE 'Europe/Helsinki';

```

## error_if_exceeds_participant_limit

return type | volatility
--- | ---
trigger | v

```sql

DECLARE
  current_registrations NUMERIC := (
    SELECT count(*) FROM registration
    WHERE exam_session_id = NEW.exam_session_id
    AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
  );
  session_limit NUMERIC := (
    SELECT max_participants FROM exam_session WHERE id = NEW.exam_session_id
  );
BEGIN
  IF current_registrations < session_limit THEN
    RETURN NEW;
  ELSE
    RAISE EXCEPTION 'max_participants of exam_session exceeded.';
  END IF;
END;

```

# Extensions

name | version | description
--- | --- | ---
plpgsql | 1.0 | PL/pgSQL procedural language

