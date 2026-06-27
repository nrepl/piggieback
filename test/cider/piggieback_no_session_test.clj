(ns cider.piggieback-no-session-test
  "Regression test for https://github.com/nrepl/piggieback/issues/124: calling
  cljs-repl outside of an nREPL session should fail with a clear message rather
  than a cryptic \"Can't change/establish root binding\" error."
  (:require
   [clojure.test :refer [deftest is]]))

(require 'cider.piggieback 'cljs.repl)

(defn- fake-repl-env []
  (reify cljs.repl/IJavaScriptEnv
    (-setup [_ _] nil)
    (-evaluate [_ _ _ _] {:status :success :value "nil"})
    (-load [_ _ _] nil)
    (-tear-down [_] nil)))

(deftest cljs-repl-outside-session-fails-clearly
  ;; This test thread is not an nREPL session, so the piggieback vars are not
  ;; thread-bound - the same situation as a Leiningen :init.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"must be invoked from within an nREPL session"
       (cider.piggieback/cljs-repl (fake-repl-env)))))
