# Code Style

- Match the style of the surrounding code. When styles conflict, prefer: current namespace > current sub-package > overall codebase.
- Do not add comments for self-explanatory code. Comment only where business domain logic forces a deviation from best practices.
- SQL queries are defined in `resources/yki/queries.sql` using jeesql-style `-- :name` annotations and loaded via `boundary/` namespaces. Keep query definitions there; do not inline SQL strings in Clojure code.
- Handler namespaces define Integrant component records and Compojure-api routes only — keep business logic in `registration/` or `util/` namespaces.
- Boundary namespaces deal with external systems (DB, HTTP, files). They must not contain business logic.
- Use `clojure.tools.logging` (`log/info`, `log/error`, etc.) for logging, not `println`.
- Format code with `lein cljfmt fix` before committing.
