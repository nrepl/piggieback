(defproject com.cemerick/piggieback "0.1.1-SNAPSHOT"
  :description "Adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/cemerick/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.clojure/clojurescript "0.0-2014"]]
  
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  
  :deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
                        "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}}
  
  :aliases  {"all" ["with-profile" "dev:dev,1.5"]}
  
  ;;maven central requirements
  :scm {:url "git@github.com:cemerick/piggieback.git"}
  :pom-addition [:developers [:developer
                              [:name "Chas Emerick"]
                              [:url "http://cemerick.com"]
                              [:email "chas@cemerick.com"]
                              [:timezone "-5"]]])
