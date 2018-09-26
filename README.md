# yki

Yleisten kielitutkintojen ilmoittaumisjärjestelmän backend.

## Developing

### Setup

When you first clone this repository, run:

```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

### Environment

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
:duct.server.http.jetty/starting-server {:port 3000}
:initiated
```

By default this creates a web server at <http://localhost:3000>.

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
```sh
lein kibit
lein nvd check
```


## Legal

Copyright (c) 2018 The Finnish National Board of Education - Opetushallitus

This program is free software:  Licensed under the EUPL, Version 1.2 or - as
soon as they will be approved by the European Commission - subsequent versions
of the EUPL (the "Licence");

You may not use this work except in compliance with the Licence.
You may obtain a copy of the Licence at: https://joinup.ec.europa.eu/software/page/eupl
