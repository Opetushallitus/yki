(defproject yki "0.1.0-SNAPSHOT"
  :description "YKI backend"
  :repositories [["oph-releases" "https://artifactory.opintopolku.fi/artifactory/oph-sade-release-local"]
                 ["oph-snapshots" "https://artifactory.opintopolku.fi/artifactory/oph-sade-snapshot-local"]
                 ["ext-snapshots" "https://artifactory.opintopolku.fi/artifactory/ext-snapshot-local"]
                 ["Scalaz Bintray Repo" "https://dl.bintray.com/scalaz/releases"]]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.rrb-vector "0.0.13"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [clj-time "0.15.0"]
                 [duct/core "0.6.2"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.4"]
                 [duct/module.sql "0.4.2"]
                 [duct/scheduler.simple "0.1.0"]
                 [com.layerware/pgqueue "0.5.1"]
                 [selmer "1.12.5"]
                 [metosin/compojure-api "2.0.0-alpha25"]
                 [metosin/jsonista "0.2.2"]
                 [metosin/muuntaja "0.6.3"]
                 [com.mjachimowicz/duct-migrations-auto-cfg "0.1.0"]
                 [metosin/spec-tools "0.8.2"]
                 [org.postgresql/postgresql "42.2.5"]
                 [duct/database.sql.hikaricp "0.4.0"]
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
                 [fi.vm.sade/auditlogger "8.3.0-SNAPSHOT"]
                 [fi.vm.sade.java-utils/java-properties "0.1.0-SNAPSHOT"]]
  :cloverage {:ns-exclude-regex [#"dev" #"user" #"yki.main" #"yki.middleware.no-auth"]}
  :plugins [[duct/lein-duct "0.10.6"]
            [lein-cljfmt "0.6.4"]
            [jonase/eastwood "0.3.3"]
            [lein-bikeshed "0.5.1"]
            [lein-ancient "0.6.15"]
            [cider/cider-nrepl "0.20.0"]
            [com.jakemccrary/lein-test-refresh "0.23.0"]
            [lein-cloverage "1.0.13"]
            [me.arrdem/lein-git-version "2.0.8"]
            [lein-kibit "0.1.6"]]
  :git-version {:version-file      "target/classes/buildversion.edn"
                :version-file-keys [:ref :version :branch :message]}
  :test-refresh {:changes-only true}
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
                  :dependencies   [[integrant/repl "0.3.1"]
                                   [eftest "0.5.4"]
                                   [cheshire "5.8.1"]
                                   [peridot "0.5.1"]
                                   [se.haleby/stub-http "0.2.5"]
                                   [com.opentable.components/otj-pg-embedded "0.13.0"]
                                   [kerodon "0.9.0"]]}})
