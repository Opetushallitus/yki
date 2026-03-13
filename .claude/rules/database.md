# Database

- PostgreSQL. Schema managed by Ragtime migrations in `resources/yki/migrations/`.
- This schema predates the repo and is shared with the new Java/Spring backend (if cloned separately). When reasoning about schema history, check migrations in both repos.
- Do not create new migration files. When assigned to update a migration, update the existing file.
- SQL query definitions live in `resources/yki/queries.sql` using jeesql `-- :name` syntax.
- DB access goes through `boundary/` namespaces using `clojure.java.jdbc`. Do not access the DB directly from handler or registration namespaces.

## Key tables (summary)

| Table                              | Purpose                                     |
| ---------------------------------- | ------------------------------------------- |
| `organizer`                        | Exam organizers                             |
| `exam_date` / `exam_date_language` | Scheduled exam dates and their languages    |
| `exam_session`                     | Individual exam sessions per organizer/date |
| `registration`                     | Participant registrations                   |
| `exam_session_queue`               | Queue for waiting registrations             |
| `login_link`                       | One-time login links                        |
| `participant_onr`                  | Cached ONR person data                      |
| `evaluation_order`                 | Uusintakoe (re-evaluation) orders           |
