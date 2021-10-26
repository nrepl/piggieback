(defproject cider/piggieback "0.5.3"
  :description "Middleware adding support for running ClojureScript REPLs over nREPL."
  :url "https://github.com/nrepl/piggieback"
  :license {:name "Eclipse Public License"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/piggieback"}
  :min-lein-version "2.0.0"

  :source-paths ["src"]

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.8.0"]
                                       [org.clojure/clojurescript "1.8.51"]
                                       [javax.xml.bind/jaxb-api "2.3.1"]
                                       [nrepl/nrepl "0.6.0"]]}

             :test {:source-paths ["env/test"]}

             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/clojurescript "1.8.51"]
                                  [javax.xml.bind/jaxb-api "2.3.1"]]}

             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.9.946"]
                                  [javax.xml.bind/jaxb-api "2.3.1"]]}

             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]
                                   [org.clojure/clojurescript "1.10.63"]]}

             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.11.0-master-SNAPSHOT"]
                                     [org.clojure/clojurescript "1.10.879"]]}

             :nrepl-0.6 {:dependencies [[nrepl/nrepl "0.6.0"]]}
             :nrepl-0.7 {:dependencies [[nrepl/nrepl "0.7.0"]]}
             :nrepl-0.8 {:dependencies [[nrepl/nrepl "0.8.3"]]}
             :nrepl-0.9 {:dependencies [[nrepl/nrepl "0.9.0-beta3"]]}

             ;; Need ^:repl because of: https://github.com/technomancy/leiningen/issues/2132
             :repl ^:repl [:test
                           {:source-paths ["env/repl"]
                            :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}]

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]}

             :eastwood {:plugins  [[jonase/eastwood "0.9.9"]]
                        :eastwood {:config-files ["eastwood.clj"]
                                   :exclude-linters [:no-ns-form-found]}}})
