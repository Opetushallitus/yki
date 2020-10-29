# YKI - Yleinen Kielitutkinto · [![Build Status](https://travis-ci.org/Opetushallitus/yki.svg?branch=master)](https://travis-ci.org/Opetushallitus/yki)

Registration system for National Certificates of Language Proficiency (YKI).

## Developing

### Setup

When you first clone this repository, run:

```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

### Environment

Before launching local development environment start PostgreSQL in docker container.

```sh
docker run --name postgres-yki -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin -p 5432:5432 -d postgres:10.4
```

Create yki database.

```sh
psql -h localhost -U admin -d yki -c 'create database yki'
```

Create local.edn with correct username and password.

```clojure
{:yki.boundary.cas/cas-client {:url-helper #ig/ref :yki.util/url-helper
                               :cas-creds {:username "replace_with_secret"
                                           :password "replace_with_password"}}}
```

To begin developing, start with a REPL.

```sh
lein repl
```

Then load the development environment.

```clojure
user=> (dev)
:loaded
```

Run `go` to prep and initiate the system.

```clojure
dev=> (go)
:duct.server.http.jetty/starting-server {:port 8080}
:initiated
```

By default this creates a web server at <http://localhost:8080>.

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```


### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

```clojure
dev=> (test)
...
```

But you can also run tests through Leiningen.

```sh
lein test
```

Rerun tests on file change.
```sh
lein test-refresh
```

Test coverage reporting.
```sh
lein cloverage
```

### Formatting
```sh
lein cljfmt check
lein cljfmt fix
```

### Static code analysis

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
