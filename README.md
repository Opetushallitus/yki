# YKI - Yleiset kielitutkinnot · [![Build Status](https://github.com/Opetushallitus/yki/actions/workflows/yki.yml/badge.svg)](https://github.com/Opetushallitus/yki/actions/workflows/yki.yml)

Backend for YKI application. Original frontend is located under <https://github.com/Opetushallitus/yki-frontend> while the new frontend can be found with the other KIOS applications under <https://github.com/Opetushallitus/kieli-ja-kaantajatutkinnot>.

## Environment setup

First, clone this repository. Ensure you have [leiningen](https://codeberg.org/leiningen/leiningen) installed.

Then, setup credentials to download dependencies from Github Packages.
Credentials are stored in the environment variables `GITHUB_USERNAME` and `GITHUB_REPOSITORY_TOKEN`.
For the token, you must create a `Personal Access Token (classic)` with scope (at least) `read:packages`.

Then, run:
```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

&nbsp;

Before launching local development environment start PostgreSQL in docker container

```sh
docker run --name postgres-yki -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin -p 5432:5432 -d postgres:10.4
```
and create yki database
```sh
psql -h localhost -U admin -c 'create database yki'
```

&nbsp;

## Development

To begin developing, start with a REPL.

```sh
lein repl
```
and load the development environment
```clojure
user=> (dev)
:loaded
```
and finally run `go` to prep and initiate the system
```clojure
dev=> (go)
:duct.server.http.jetty/starting-server {:port 8080}
:initiated
```

&nbsp;

This creates a web server at <http://localhost:8080> and runs the migrations defined under `resources/yki/migrations` unless they have been run before. After the migrations are run you may want to initialise the database by running `dev/init.sql` script for example from the root directory as
```sh
psql -h localhost -U admin -d yki -c '\i dev/init.sql'
```

&nbsp;

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```

### Local configuration

Local.edn example template can be found under [local-configuration](local-configuration/local.edn.template). This template does not use user authentication and has a set hard coded user. It also has all the sync jobs disabled.

### Postman collection

Project has a postman collection to ease up development. Collection and environment can be found [here](docs/postman) and need to be manually imported. Tables mentioned in the "Local configuration and development" should have some dummy data in them. Referencing to this data, following Postman environment variables should be updated to correspond these values:

- oid (oid content from organizer)
- exam_date (exam_date content from exam_date)
- date_id (id content from exam_date)

Rest of the environment variables are updated as follows:

* When creating a new exam session, set date_id is referenced and returned session id is set to variable {{session_id}}. Other 'Virkailija: Exam session management' operations are referencing to this session id.
* When 'oppija' registration is initialized, set session_id is referenced and returned registration id is set to variable {{registration_id}}

### External development credentials

[Suomi.fi](https://palveluhallinta.suomi.fi/fi/tuki/artikkelit/5a82ef7ab03cdc41de664a2b)

### Testing

Testing is the fastest through the REPL, as you avoid environment startup time:
```clojure
dev=> (test)
...
```

But you can also run all tests through Leiningen as
```sh
lein test
```
or run tests just for a single file as
```sh
lein test :only yki.handler.exam-date-test
```
where `yki.handler.exam-date-test` as an example stands for the namespace defined under `test/yki/handler/exam_date_test.clj`.

&nbsp;

To rerun tests on file change:
```sh
lein test-refresh
```

Test coverage reporting:

```sh
lein cloverage
```
### Formatting

```sh
lein cljfmt check
lein cljfmt fix
```

### **Static code analysis**

[clj-kondo](https://github.com/clj-kondo/clj-kondo)

Install `clj-kondo` first, eg. with `brew install borkdude/brew/clj-kondo`.

Before using clj-kondo, we first need to set it up properly for this project.
For more details, check out (https://github.com/clj-kondo/clj-kondo#project-setup).
Briefly, the following command needs to be run to let `clj-kondo` analyze the dependencies and create a cache for future use.
```sh
clj-kondo --lint "$(lein classpath)" --dependencies --parallel --copy-configs
```

Now, we're ready to use `clj-kondo` to get feedback on our code:
```sh
clj-kondo --lint src/
clj-kondo --lint test/
```

Configurations for `clj-kondo` are stored under `.clj-kondo/`, and they can be modified to eg. suppress false positives.

For best results, integrate `clj-kondo` with your IDE for rapid feedback.
See [IDE integration guide](https://github.com/clj-kondo/clj-kondo/blob/master/doc/editor-integration.md).

Linting is also performed as a job on the CI.
The job is configured to fail if `clj-kondo` finds any errors under `src` or `test` directories.


[eastwood](https://github.com/jonase/eastwood)

```sh
lein eastwood
```

[kibit](https://github.com/jonase/kibit)

```sh
lein kibit
```

[bikeshed](https://github.com/dakrone/lein-bikeshed)

```sh
lein bikeshed
```

### Release

```sh
lein release :major, :minor or :patch
```

## Glossary

| *Term* | *Description* |
| SOLKI | TBD |
| ONR | TBD |

## Architecture

### Job Queues

YKI uses database backed job queues to prevent certain tasks from running more than once. The queues themselves are implemented using [pgqueue](https://github.com/layerware/pgqueue) and it is recommended to familiarize yourself with how this library works to understand the rest of the job queue code.

#### `email-q`

_Email requests_ added to this queue will be handled by the `email-queue-reader` scheduled task.

#### `data-sync-q`

_Data synchronization requests_ added to this queue will be handled by the `data-sync-queue-reader` scheduled task.

### Scheduled Tasks

YKI uses asynchronous scheduled tasks to keep data up-to-date and for maintenance tasks. Job queue system is used by some tasks to prevent running the same task twice to avoid data duplication issues.

#### `registration-state-handler`

Handles general state of single registration state, effectively marking incomplete registrations to expired and other similar state changes which are time bound, such as not having paid the exam fee on time.

#### `participants-sync-handler`

Synchronizes (TODO: to SOLKI?) completed exam session registration participant data.

#### `email-queue-reader`

Handles sending emails from email queues to recipients. Emails to be sent and queue handling are managed by the `exam-session-queue-handler` task.

#### `data-sync-queue-reader`

Handles exam session and organizer data synchronization (TODO: to SOLKI?), including deletion.

#### `exam-session-queue-handler`

Manages logic for selecting the correct email template to be sent to people in various email queues. Uses database-backed job queue to prevent sending same email multiple times.

## Legal

Copyright (c) 2013-2020 Finnish National Agency for Education - Opetushallitus

This program is free software:  Licensed under the EUPL, Version 1.2 or - as
soon as they will be approved by the European Commission - subsequent versions
of the EUPL (the "Licence");

You may not use this work except in compliance with the Licence.
You may obtain a copy of the Licence at: https://joinup.ec.europa.eu/software/page/eupl
