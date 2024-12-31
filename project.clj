(defproject cider/piggieback "0.6.0"
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

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.12.0"]
                                       [org.clojure/clojurescript "1.11.132"]
                                       [nrepl/nrepl "1.0.0"]]}

             :test {:source-paths ["env/test"]}

             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]
                                   [org.clojure/clojurescript "1.10.914"]]}

             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]
                                   [org.clojure/clojurescript "1.11.132"]]}

             :1.12 {:dependencies [[org.clojure/clojure "1.12.0"]
                                   [org.clojure/clojurescript "1.11.132"]]}

             :nrepl-1.0 {:dependencies [[nrepl/nrepl "1.0.0"]]}
             :nrepl-1.1 {:dependencies [[nrepl/nrepl "1.1.1"]]}
             :nrepl-1.2 {:dependencies [[nrepl/nrepl "1.2.0"]]}
             :nrepl-1.3 {:dependencies [[nrepl/nrepl "1.3.0"]]}

             ;; Need ^:repl because of: https://github.com/technomancy/leiningen/issues/2132
             :repl ^:repl [:test
                           {:source-paths ["env/repl"]
                            :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}]

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]}

             :eastwood {:plugins  [[jonase/eastwood "0.9.9"]]
                        :eastwood {:config-files ["eastwood.clj"]
                                   :exclude-linters [:no-ns-form-found]}}})
