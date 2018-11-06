(defproject yki "0.1.0-SNAPSHOT"
  :description "YKI backend"
  :repositories [["oph-releases" "https://artifactory.opintopolku.fi/artifactory/oph-sade-release-local"]
                 ["oph-snapshots" "https://artifactory.opintopolku.fi/artifactory/oph-sade-snapshot-local"]
                 ["ext-snapshots" "https://artifactory.opintopolku.fi/artifactory/ext-snapshot-local"]
                 ["Scalaz Bintray Repo" "https://dl.bintray.com/scalaz/releases"]]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [duct/core "0.6.2"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.4"]
                 [duct/module.sql "0.4.2"]
                 [duct/scheduler.simple "0.1.0"]
                 [metosin/compojure-api "2.0.0-alpha25"]
                 [metosin/jsonista "0.2.1"]
                 [metosin/muuntaja "0.6.0"]
                 [com.mjachimowicz/duct-migrations-auto-cfg "0.1.0"]
                 [metosin/spec-tools "0.7.1"]
                 [org.postgresql/postgresql "42.2.4"]
                 [duct/database.sql.hikaricp "0.3.3"]
                 [buddy/buddy-auth "2.1.0"]
                 [webjure/jeesql "0.4.7"]
                 [http-kit "2.3.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.clojure/core.memoize "0.7.1"]
                 [ring-logger "1.0.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 ;; these two are necessary for Scala Cas Client
                 [org.http4s/blaze-http_2.11 "0.10.1" :upgrade false]
                 [org.http4s/http4s-json4s-native_2.11 "0.10.1" :upgrade false]
                 [oph/clj-util "0.1.0" :exclusions [org.http4s/blaze-http_2.11]]
                 [fi.vm.sade/auditlogger "8.2.0-SNAPSHOT"]
                 [fi.vm.sade.java-utils/java-properties "0.1.0-SNAPSHOT"]]
  :cloverage {:ns-exclude-regex [#"dev" #"user" #"yki.main" #"yki.middleware.no-auth"]}
  :plugins [[duct/lein-duct "0.10.6"]
            [lein-cljfmt "0.6.1"]
            [jonase/eastwood "0.2.9"]
            [lein-bikeshed "0.5.1"]
            [com.jakemccrary/lein-test-refresh "0.23.0"]
            [lein-cloverage "1.0.13"]
            [lein-kibit "0.1.6"]]
  :main ^:skip-aot yki.main
  :jvm-opts ["-Duser.timezone=UTC"]
  :resource-paths ["resources" "target/resources"]
  :prep-tasks     ["javac" "compile" ["run" ":duct/compiler"]]
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :test {:jvm-opts ["-Dlogback.configurationFile=test/resources/logback-test.xml"]}
   :repl {:prep-tasks   ^:replace ["javac" "compile"]
          :repl-options {:init-ns user}}
   :uberjar {:aot :all}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :dependencies   [[integrant/repl "0.2.0"]
                                   [eftest "0.4.1"]
                                   [cheshire "5.8.0"]
                                   [cider/cider-nrepl "0.15.1-SNAPSHOT"]
                                   [peridot "0.5.1"]
                                   [se.haleby/stub-http "0.2.5"]
                                   [com.opentable.components/otj-pg-embedded "0.12.1"]
                                   [kerodon "0.9.0"]]}})
