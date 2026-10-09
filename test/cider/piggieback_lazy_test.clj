(ns cider.piggieback-lazy-test
  "Loading the ClojureScript compiler takes a while, so piggieback leaves it
  alone until a session starts a ClojureScript REPL. The other tests load it,
  so this one checks in a JVM of its own."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [clojure.test :refer [deftest is]])
  (:import
   (java.io File)))

(def ^:private script
  "Evaluates Clojure and asks for a describe in a session of a server with
  piggieback, then prints what describe said about ClojureScript and whether
  the compiler got loaded."
  (pr-str
   '(do
      (require '[nrepl.core :as nrepl] '[nrepl.server :as server] 'cider.piggieback)
      (with-open [server (server/start-server
                          :bind "127.0.0.1"
                          :handler (server/default-handler #'cider.piggieback/wrap-cljs-repl))]
        (let [session (-> (nrepl/connect :port (:port server))
                          (nrepl/client Long/MAX_VALUE)
                          nrepl/client-session)]
          (dorun (nrepl/message session {:op "eval" :code "(+ 1 2)"}))
          (prn [(-> (nrepl/message session {:op "describe"}) first :aux :piggieback)
                (boolean (some find-ns '[cljs.analyzer cider.piggieback.cljs]))])))
      (flush)
      (System/exit 0))))

(defn- classpath
  "The tests' classpath, less env/repl, whose user.clj loads ClojureScript
  when the tests run from `lein repl`."
  []
  (->> (string/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
       (remove #(string/ends-with? % (str "env" File/separator "repl")))
       (string/join File/pathSeparator)))

(deftest compiler-waits-for-a-cljs-repl
  (let [{:keys [exit out err]} (shell/sh (str (System/getProperty "java.home") "/bin/java")
                                         "-cp" (classpath)
                                         "clojure.main" "-e" script)]
    (is (zero? exit) (str err))
    (is (= [{:cljs-repl "inactive"} false]
           (read-string (last (string/split-lines out)))))))
