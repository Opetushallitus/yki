(defproject yki "0.1.0-SNAPSHOT"
  :description "YKI backend"
  :repositories [["oph-releases" "https://artifactory.opintopolku.fi/artifactory/oph-sade-release-local"]
                 ["oph-snapshots" "https://artifactory.opintopolku.fi/artifactory/oph-sade-snapshot-local"]
                 ["ext-snapshots" "https://artifactory.opintopolku.fi/artifactory/ext-snapshot-local"]]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [duct/core "0.6.2"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.4"]
                 [duct/module.sql "0.4.2"]
                 [com.mjachimowicz/duct-migrations-auto-cfg "0.1.0"]
                 [org.postgresql/postgresql "42.2.4"]
                 [webjure/jeesql "0.4.7"]]
  :plugins [[duct/lein-duct "0.10.6"]]
  :main ^:skip-aot yki.main
  :jvm-opts ["-Duser.timezone=UTC"]
  :resource-paths ["resources" "target/resources"]
  :prep-tasks     ["javac" "compile" ["run" ":duct/compiler"]]
  :profiles
  {:dev  [:project/dev :profiles/dev]
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
                                   [com.opentable.components/otj-pg-embedded "0.12.0"]
                                   [kerodon "0.9.0"]]}})
