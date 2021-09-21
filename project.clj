(defproject yki "1.0.1-SNAPSHOT"
  :description "YKI backend"
  :repositories [["oph-releases" "https://artifactory.opintopolku.fi/artifactory/oph-sade-release-local"]
                 ["oph-snapshots" "https://artifactory.opintopolku.fi/artifactory/oph-sade-snapshot-local"]
                 ["ext-snapshots" "https://artifactory.opintopolku.fi/artifactory/ext-snapshot-local"]
                 ["Scalaz Bintray Repo" "https://dl.bintray.com/scalaz/releases"]]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/java.jdbc "0.7.12"] ;Development has ended and replaced by next.jdbc
                 [org.clojure/tools.logging "1.1.0"]
                 [org.clojure/core.memoize "1.0.250"]
                 [org.clojure/data.xml "0.0.8"]
                 [clj-time "0.15.2"] ;deprecated
                 [duct/core "0.8.0"]
                 [duct/module.cljs "0.4.1"]
                 [duct/module.logging "0.5.0"]
                 [duct/module.sql "0.6.1"]
                 [duct/module.web "0.7.3"]
                 [duct/scheduler.simple "0.1.0"]
                 [com.layerware/pgqueue "0.5.1"]
                 [selmer "1.12.44"]
                 [metosin/compojure-api "2.0.0-alpha31"]
                 [metosin/jsonista "0.2.7"]
                 [metosin/muuntaja "0.6.4"]
                 [metosin/spec-tools "0.9.1"]
                 [org.postgresql/postgresql "42.2.5"]
                 [duct/database.sql.hikaricp "0.4.0"]
                 [buddy/buddy-auth "2.1.0"] ; Maitaner mode, no active maintainer
                 [webjure/jeesql "0.4.7"]
                 [http-kit "2.5.3"]
                 [ring-logger "1.0.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 ;; these are necessary for Scala Cas Client
                 [oph/clj-cas "0.6.1-SNAPSHOT"]
                 [fi.vm.sade/scala-cas_2.12 "2.1.0-SNAPSHOT"]
                 [org.http4s/http4s-blaze-client_2.12 "0.16.6a"]
                 [org.http4s/blaze-http_2.12 "0.12.11"]
                 [fi.vm.sade/auditlogger "8.3.0-SNAPSHOT"]
                 [fi.vm.sade.java-utils/java-properties "0.1.0-SNAPSHOT"]]
  :cloverage {:ns-exclude-regex [#"dev" #"user" #"yki.main" #"yki.middleware.no-auth" #"yki.migrations"]}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :plugins [[duct/lein-duct "0.12.3"]
            [lein-cljfmt "0.8.0"]
            [jonase/eastwood "0.9.9"]
            [lein-bikeshed "0.5.2"]
            [lein-ancient "0.7.0"]
            [com.jakemccrary/lein-test-refresh "0.25.0"]
            [lein-cloverage "1.2.2"]
            [me.arrdem/lein-git-version "2.0.8"]
            [lein-kibit "0.1.8"]]
  :git-version {:version-file      "target/classes/buildversion.edn"
                :version-file-keys [:ref :version :branch :message]}
  :test-refresh {:changes-only true}
  :middleware     [lein-duct.plugin/middleware lein-git-version.plugin/middleware]
  :main ^:skip-aot yki.main
  :jvm-opts ["-Duser.timezone=UTC"]
  :resource-paths ["resources" "target/resources"]
  :prep-tasks     ["javac" "compile" ["run" ":duct/compiler"]]
  :global-vars {*warn-on-reflection* true}
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :test {:jvm-opts ["-Dlogback.configurationFile=test/resources/logback-test.xml"]}
   :repl {:prep-tasks   ^:replace ["javac" "compile"]
          :repl-options {:init-ns user}}
   :uberjar {:aot :all}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :dependencies   [[integrant/repl "0.3.1"]
                                   [eftest "0.5.9"]
                                   [cheshire "5.10.0"]
                                   [se.haleby/stub-http "0.2.10"]
                                   [com.opentable.components/otj-pg-embedded "0.13.4"]
                                   [kerodon "0.9.1"]]}})
