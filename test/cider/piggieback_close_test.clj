(ns cider.piggieback-close-test
  "Regression test: closing an nREPL session must tear down an active
  ClojureScript repl-env, so a client that closes (or exits) without sending
  `:cljs/quit` doesn't leak the JavaScript runtime.

  Node-free: a `RecordingEnv` stands in for a real repl-env and records whether
  it was torn down, so we don't need (and can't have, per the cljs.repl.node
  global-state limitation) a second live Node REPL alongside the main fixture."
  (:require
   [clojure.test :refer [deftest is]]
   [cider.piggieback]
   [cider.piggieback.cljs :as core]
   [cljs.repl]))

(defrecord RecordingEnv [torn-down?]
  core/GetReplEnv
  (get-repl-env [this] this)
  cljs.repl/IJavaScriptEnv
  (-setup [_ _])
  (-evaluate [_ _ _ _])
  (-load [_ _ _])
  (-tear-down [_] (reset! torn-down? true)))

(deftest closing-session-tears-down-active-cljs-repl
  (let [torn? (atom false)
        orig-closed? (atom false)
        session (atom {#'cider.piggieback/*cljs-repl-env* (->RecordingEnv torn?)}
                      :meta {:id "s" :close #(reset! orig-closed? true)})
        ensure-close-teardown! @#'cider.piggieback.cljs/ensure-close-teardown!]
    (ensure-close-teardown! session)
    ;; simulate nREPL's close-session, which invokes the session's :close meta
    ((:close (meta session)))
    (is (true? @torn?) "the active cljs repl-env was torn down")
    (is (true? @orig-closed?) "the original session :close still ran")))

(deftest teardown-hook-is-idempotent-and-safe-when-inactive
  (let [orig-closed? (atom false)
        ;; no active cljs repl-env in the session
        session (atom {} :meta {:id "s" :close #(reset! orig-closed? true)})
        ensure-close-teardown! @#'cider.piggieback.cljs/ensure-close-teardown!]
    (ensure-close-teardown! session)
    ;; hooking twice must not stack wrappers
    (ensure-close-teardown! session)
    ((:close (meta session)))
    (is (true? @orig-closed?) "closing still works with no active cljs repl")))
