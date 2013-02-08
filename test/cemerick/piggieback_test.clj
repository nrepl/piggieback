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
    ; need to let the dynamic bindings get in place before trying to eval anything that
    ; depends upon those bingings being set
    (doall (nrepl/message session {:op "eval" :code "(cemerick.piggieback/cljs-repl)"}))
    ; I think there's a race condition between when previous expressions are evaluated
    ; (which nREPL serializes for a session) and when the next code to be evaluated is analyzed
    ; in piggieback (which has no visibility into the session serialization mechanism). The
    ; fix is to work like a REPL _should_, i.e. wait for the full response of an evaluation
    ; prior to sending out another chunk of code.
    (doall (nrepl/message session {:op "eval" :code "(defn x [] (into [] (js/Array 1 2 3)))"}))
    (is (= [1 2 3] (->> {:op "eval" :code "(x)"} (nrepl/message session) nrepl/response-values first)))))