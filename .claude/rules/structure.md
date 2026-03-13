# Project Structure

YKI is a legacy Clojure backend for the National Certificates of Language Proficiency (Yleinen kielitutkinto) registration system. It is being migrated into a separate Java/Spring repo. Avoid adding new features here; prefer maintenance and bug fixes.

## Stack

- **Framework:** Duct (Integrant-based component system)
- **HTTP:** Ring + Compojure-api + Jetty
- **DB:** PostgreSQL via `clojure.java.jdbc`, migrations via Ragtime
- **Build:** Leiningen (`project.clj`)

## Source layout

```
src/yki/
  api.clj                  — Integrant system entry point
  handler/
    routing.clj            — Route constants (URL paths)
    auth.clj               — CAS/oppija authentication endpoints
    registration.clj       — Exam registration HTTP endpoints
    exam_session.clj       — Virkailija exam session management
    exam_session_public.clj — Public exam session queries
    exam_date.clj          — Exam date management (virkailija)
    exam_date_public.clj   — Public exam date queries
    payment.clj            — Legacy payment flow
    exam_payment_new.clj   — New Paytrail exam payment
    evaluation.clj         — Evaluation (uusintakoe) management
    evaluation_payment.clj — Legacy evaluation payment
    evaluation_payment_new.clj — New Paytrail evaluation payment
    organizer.clj          — Organizer management
    login_link.clj         — Login link generation/handling
    localisation.clj       — Localisation proxy
    file.clj               — File upload/download (Liiteri)
    code.clj               — Auth code handling
  middleware/
    auth.clj               — Authentication middleware (CAS + oppija)
    no_auth.clj            — Development no-auth middleware
    access_log.clj         — Access logging
    payment.clj            — Payment middleware
  boundary/
    cas.clj                — CAS client integration
    cas_ticket_db.clj      — CAS ticket persistence
    onr.clj                — ONR (person registry) client
    permissions.clj        — Permissions service client
    registration_db.clj    — Registration DB queries
    exam_session_db.clj    — Exam session DB queries
    exam_date_db.clj       — Exam date DB queries
    organizer_db.clj       — Organizer DB queries
    evaluation_db.clj      — Evaluation DB queries
    exam_payment_new_db.clj — New payment DB queries
    login_link_db.clj      — Login link DB queries
    job_db.clj             — Job/task DB queries
    email.clj              — Email sending (via queue)
    localisation.clj       — Localisation service client
    yki_register.clj       — YKI register integration
    files.clj              — Liiteri file store client
    db_extensions.clj      — JDBC type extensions
  registration/
    registration.clj       — Core registration business logic
    email.clj              — Registration email templates/dispatch
    payment_e2.clj         — Paytrail E2 payment integration
    payment_e2_util.clj    — Payment utility functions
  auth/
    cas_auth.clj           — CAS authentication flow
    code_auth.clj          — Auth code flow
    header_auth.clj        — Header-based auth (dev/proxy)
  util/
    url_helper.clj         — URL construction
    http_util.clj          — HTTP client helpers
    audit_log.clj          — Audit logging
    common.clj             — Shared utilities
    exam_payment_helper.clj    — Exam payment orchestration
    evaluation_payment_helper.clj — Evaluation payment orchestration
    template_util.clj      — Selmer HTML template rendering
    log_util.clj           — Logging utilities
  job/
    scheduled_tasks.clj    — Scheduled jobs (email queue, sync, state handler)
```

## Tests

```
test/yki/
  handler/       — Handler-level integration tests (peridot/kerodon)
  boundary/      — Boundary/DB tests
  util/          — Utility tests
  test/resources/ — Fixtures, JSON test data, logback config
```

## Configuration

- `resources/yki/config.edn` — Base Duct config (all Integrant keys)
- `dev/resources/dev.edn` — Dev profile overrides
- `local-configuration/local.edn.template` — Per-developer local secrets template
- `oph-configuration/config.edn.template` — Production config template
