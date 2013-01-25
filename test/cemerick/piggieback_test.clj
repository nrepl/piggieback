(ns cemerick.piggieback-test
  (:require [cemerick.piggieback :as pb]
            [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [server :as server]))
  (:use clojure.test))

(def ^{:dynamic true} *server-port* nil)

(defn repl-server-fixture
  [f]
  (with-open [server (server/start-server
                       :handler (server/default-handler #'cemerick.piggieback/wrap-cljs-repl))]
    (binding [*server-port* (.getLocalPort (:ss @server))]
      (f))))

(use-fixtures :once repl-server-fixture)

(deftest default-sanity
  (let [conn (nrepl/connect :port *server-port*)
        session (nrepl/client-session (nrepl/client conn Long/MAX_VALUE))]
    (doall (nrepl/message session {:op "eval" :code "(cemerick.piggieback/cljs-repl)"}))
    (doall (nrepl/message session {:op "eval" :code "(defn x [] (into [] (js/Array 1 2 3)))"}))
    (is (= [1 2 3] (->> {:op "eval" :code "(x)"} (nrepl/message session) nrepl/response-values first)))))