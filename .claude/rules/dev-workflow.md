# Development Workflow

## Prerequisites

PostgreSQL running locally:

```sh
docker run --name postgres-yki -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin -p 5432:5432 -d postgres:10.4
psql -h localhost -U admin -c 'create database yki'
```

First-time setup:

```sh
lein duct setup
```

Copy `local-configuration/local.edn.template` to `local-configuration/local.edn` and fill in credentials.

## Starting the server

```sh
rm -rf target/ # macos load migration files twice otherwise
lein repl
```

```clojure
user=> (dev)    ; load dev environment
dev=> (go)      ; start system on http://localhost:8080
dev=> (reset)   ; reload changed files
```

## Running tests

Fastest from REPL:

```clojure
dev=> (test)
```

Or via Leiningen:

```sh
lein test              # run all tests
lein test-refresh      # rerun on file change
lein cloverage         # test coverage report
```

## Code quality

```sh
lein cljfmt check      # check formatting
lein cljfmt fix        # auto-fix formatting
lein eastwood          # lint
lein kibit             # idiomatic Clojure suggestions
lein bikeshed          # style warnings
```

## Release

```sh
lein release :patch   # or :minor / :major
```
