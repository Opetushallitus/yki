(defproject yki "1.0.1-SNAPSHOT"
  :description "YKI backend"
  :repositories [["oph-github-packages" {:url "https://maven.pkg.github.com/Opetushallitus/packages"
                                         :username :env/GITHUB_USERNAME
                                         :password :env/GITHUB_REGISTRY_TOKEN}]
                 ["oph-releases" "https://artifactory.opintopolku.fi/artifactory/oph-sade-release-local"]
                 ["oph-snapshots" "https://artifactory.opintopolku.fi/artifactory/oph-sade-snapshot-local"]
                 ["ext-snapshots" "https://artifactory.opintopolku.fi/artifactory/ext-snapshot-local"]
                 ["Scalaz Bintray Repo" "https://dl.bintray.com/scalaz/releases"]]
  :min-lein-version "2.0.0"
  :managed-dependencies [[com.fasterxml.jackson.core/jackson-annotations "2.17.2"]
                         [com.fasterxml.jackson.core/jackson-core "2.17.2"]
                         [com.fasterxml.jackson.core/jackson-databind "2.17.2"]
                         [com.fasterxml.jackson.datatype/jackson-datatype-jsr310 "2.17.2"]]
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/data.csv "1.1.0"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.clojure/core.memoize "1.1.266"]
                 [clj-time "0.15.2"]
                 [duct/core "0.8.1"]
                 [duct/module.logging "0.5.0"]
                 [duct/module.sql "0.6.1"]
                 [duct/module.web "0.7.3"]
                 [duct/scheduler.simple "0.1.0"]
                 [com.layerware/pgqueue "0.5.1"]
                 [selmer "1.12.61"]
                 [metosin/compojure-api "2.0.0-alpha31"
                  :exclusions [joda-time]]
                 [metosin/jsonista "0.3.10"]
                 [metosin/muuntaja "0.6.10"]
                 [metosin/spec-tools "0.10.7"]
                 [org.postgresql/postgresql "42.7.4"]
                 [duct/database.sql.hikaricp "0.4.0"]
                 [buddy/buddy-auth "3.0.323"]
                 [webjure/jeesql "0.4.7"]
                 [http-kit "2.8.0"]
                 [ring-logger "1.1.1"]
                 [ch.qos.logback/logback-classic "1.5.7"]
                 [org.clojure/data.xml "0.0.8"]
                 [fi.vm.sade.java-utils/java-cas "1.2.1-SNAPSHOT"
                  :exclusions [org.slf4j/slf4j-simple]]
                 [fi.vm.sade/auditlogger "9.2.4-SNAPSHOT"]
                 [fi.vm.sade.java-utils/java-properties "0.1.0-SNAPSHOT"]
                 [com.github.jhonnymertz/java-wkhtmltopdf-wrapper "1.1.14-RELEASE"]
                 [org.clojars.pkoivisto/clj-json-patch "0.1.9"]]
  :exclusions [org.slf4j/slf4j-nop
               commons-logging]
  :cloverage {:ns-exclude-regex [#"dev" #"user" #"yki.main" #"yki.middleware.no-auth" #"yki.migrations"]}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :plugins [[duct/lein-duct "0.12.3"]
            [lein-cljfmt "0.6.4"]
            [jonase/eastwood "0.3.3"]
            [lein-bikeshed "0.5.1"]
            [lein-ancient "1.0.0-RC3"]
            [com.jakemccrary/lein-test-refresh "0.23.0"]
            [lein-cloverage "1.0.13"]
            [me.arrdem/lein-git-version "2.0.8"]
            [lein-kibit "0.1.6"]]
  :git-version {:version-file      "target/classes/buildversion.edn"
                :version-file-keys [:ref :version :branch :message]}
  :test-refresh {:changes-only true}
  :middleware [lein-duct.plugin/middleware lein-git-version.plugin/middleware]
  :main ^:skip-aot yki.main
  :jvm-opts ["-Duser.timezone=Europe/Helsinki"
             "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]
  :resource-paths ["resources" "target/resources"]
  :prep-tasks ["javac" "compile" ["run" ":duct/compiler"]]
  :global-vars {*warn-on-reflection* true}
  :profiles
  {:provided     {:dependencies [[hawk "0.2.11" :exclusions [net.java.dev.jna/jna]]]}
   :dev          [:project/dev :profiles/dev]
   :test         {:jvm-opts ["-Dlogback.configurationFile=test/resources/logback-test.xml"]
                  :resource-paths ["test/resources"]}
   :repl         {:prep-tasks   ^:replace ["javac" "compile"]
                  :repl-options {:init-ns user}}
   :uberjar      {:aot :all}
   :profiles/dev {}
   :project/dev  {:jvm-opts ["-Djdk.attach.allowAttachSelf"
                             "-XX:+UnlockDiagnosticVMOptions"
                             "-XX:+DebugNonSafepoints"]
                  :source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :dependencies   [[integrant/repl "0.3.3"]
                                   [eftest "0.6.0"]
                                   [peridot "0.5.4"]
                                   [se.haleby/stub-http "0.2.14"]
                                   [com.opentable.components/otj-pg-embedded "1.1.0"]
                                   [kerodon "0.9.1"]
                                   [com.clojure-goes-fast/clj-async-profiler "1.2.2"]]
                  :managed-dependencies [[org.testcontainers/testcontainers "1.20.1"]
                                         [org.testcontainers/postgresql "1.20.1"]]}})
