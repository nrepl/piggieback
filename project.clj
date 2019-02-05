(defproject cider/piggieback "0.4.0-SNAPSHOT"
  :description "Middleware adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/nrepl/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/piggieback"}
  :min-lein-version "2.0.0"

  :source-paths ["src"]

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]]

  :aliases  {"all" ["with-profile" "dev"]}

  :profiles {:provided [:1.8 :nrepl-0.5]

             :test {:source-paths ["env/test"]}

             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/clojurescript "1.8.51"]
                                  [javax.xml.bind/jaxb-api "2.3.1"]]}

             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.9.946"]
                                  [javax.xml.bind/jaxb-api "2.3.1"]]}

             :1.10 {:dependencies [[org.clojure/clojure "1.10.0"]
                                   [org.clojure/clojurescript "1.10.63"]]}

             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.11.0-master-SNAPSHOT"]
                                     [org.clojure/clojurescript "1.10.439"]]}

             :nrepl-0.4 {:dependencies [[nrepl/nrepl "0.4.5"]]}
             :nrepl-0.5 {:dependencies [[nrepl/nrepl "0.5.3"]]}
             :nrepl-0.6 {:dependencies [[nrepl/nrepl "0.6.0"]]}

             ;; Need ^:repl because of: https://github.com/technomancy/leiningen/issues/2132
             :repl ^:repl [:test
                           {:source-paths ["env/repl"]
                            :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}]

             :sysutils {:plugins [[lein-sysutils "0.2.0"]]}

             :cloverage [:test
                         {:plugins [[lein-cloverage "1.0.13"]]
                          :cloverage {:codecov? true}}]

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]}

             :eastwood {:plugins  [[jonase/eastwood "0.3.4"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
