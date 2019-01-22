(defproject cider/piggieback "0.3.10"
  :description "Middleware adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/nrepl/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/piggieback"}
  :min-lein-version "2.0.0"
  :dependencies [[nrepl/nrepl "0.4.5"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.946"]]

  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]]

  :aliases  {"all" ["with-profile" "dev"]}

  ;; painful for users; https://github.com/technomancy/leiningen/issues/1771
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.13"]
                                  [javax.xml.bind/jaxb-api "2.3.1"]]
                   :source-paths ["src" "dev"]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.9.946"]]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.10.0-master-SNAPSHOT"]
                                     [org.clojure/clojurescript "1.9.946"]]}

             :sysutils {:plugins [[lein-sysutils "0.2.0"]]}

             :cloverage {:plugins [[lein-cloverage "1.0.13"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]}

             :eastwood {:plugins  [[jonase/eastwood "0.3.4"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
