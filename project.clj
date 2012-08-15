(defproject com.cemerick/piggieback "0.0.2-SNAPSHOT"
  :description "Adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/cemerick/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.0-SNAPSHOT"]
                 [org.clojure/clojurescript "0.0-1450"]]
  
  :injections [(require 'cemerick.piggieback)
               (require 'clojure.tools.nrepl.middleware)] 
  :nrepl-handler (-> clojure.tools.nrepl.server/unknown-op
                   clojure.tools.nrepl.middleware/wrap-describe
                   clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
                   clojure.tools.nrepl.middleware.load-file/wrap-load-file
                   cemerick.piggieback/wrap-cljs-repl
                   clojure.tools.nrepl.middleware.pr-values/pr-values
                   clojure.tools.nrepl.middleware.session/add-stdin
                   clojure.tools.nrepl.middleware.session/session)
  
  :profiles {:dev {:plugins [[lein-clojars "0.9.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.0-SNAPSHOT"]]}}
  
  :repositories {"snapshots" "https://oss.sonatype.org/content/repositories/snapshots/"}
  
  :deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
                        "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}}
  
  ;;maven central requirements
  :scm {:url "git@github.com:cemerick/piggieback.git"}
  :pom-addition [:developers [:developer
                              [:name "Chas Emerick"]
                              [:url "http://cemerick.com"]
                              [:email "chas@cemerick.com"]
                              [:timezone "-5"]]])
