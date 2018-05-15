(defproject cider/piggieback "0.3.4"
  :description "Adding support for running ClojureScript REPLs over nREPL."
  :url "http://github.com/clojure-emacs/piggieback"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.946"]]

  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]]

  :aliases  {"all" ["with-profile" "dev"]}

  ;; painful for users; https://github.com/technomancy/leiningen/issues/1771
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.13"]]
                   :source-paths ["src" "dev"]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.9.946" :scope "provided"]]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.10.0-master-SNAPSHOT"]
                                     [org.clojure/clojurescript "1.9.946" :scope "provided"]]}

             :sysutils {:plugins [[lein-sysutils "0.2.0"]]}

             :cloverage {:plugins [[lein-cloverage "1.0.11-SNAPSHOT"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.5.7"]]}

             :eastwood {:plugins  [[jonase/eastwood "0.2.5"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
