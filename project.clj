(defproject com.cemerick/piggieback "0.2.2"
  :description "Adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/cemerick/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.6.0" :scope "provided"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [org.clojure/clojurescript "0.0-3165"]]
  
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  
  :deploy-repositories {"releases" {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
                        "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}}

  :aliases  {"all" ["with-profile" "dev"]}

  ; painful for users; https://github.com/technomancy/leiningen/issues/1771
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.10"]]}}
  
  ;;maven central requirements
  :scm {:url "git@github.com:cemerick/piggieback.git"}
  :pom-addition [:developers [:developer
                              [:name "Chas Emerick"]
                              [:url "http://cemerick.com"]
                              [:email "chas@cemerick.com"]
                              [:timezone "-5"]]])
