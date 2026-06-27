(ns cider.piggieback-setup-error-test
  "Regression test for https://github.com/nrepl/piggieback/issues/62.

  Uses a fake repl env rather than a real one (e.g. Node) so the setup error can
  be injected without a JavaScript runtime: a real runtime left half-initialized
  by the failed setup would simply block on the next evaluation, which is a
  separate problem and would make this test hang."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(require 'cider.piggieback 'cljs.repl)

;; A repl env that pretends every JavaScript evaluation succeeds, so the test
;; never depends on (or blocks on) an actual JS runtime.
(defrecord FakeReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [_ _] nil)
  (-evaluate [_ _ _ _] {:status :success :value "42"})
  (-load [_ _ _] nil)
  (-tear-down [_] nil))

;; The initial require references a bogus namespace, so the REPL's setup eval
;; errors out while the env itself is left perfectly usable.
;; A dedicated output dir keeps this test's ClojureScript compilation from
;; clobbering the default "out" dir shared with the Node-based test suite.
(def ^:private start-code
  (nrepl/code
   (cider.piggieback/cljs-repl
    (cider.piggieback-setup-error-test/->FakeReplEnv)
    :output-dir "target/piggieback-setup-error-out"
    :repl-requires '[[totally.bogus.does-not-exist]])))

;; Before the fix, a setup error left *cljs-compiler-env* unset, so every
;; subsequent evaluation blew up (NPE in cljs.analyzer/get-namespace) or
;; recompiled cljs.core from scratch.
(deftest evaluation-works-after-setup-error
  (with-open [^nrepl.server.Server server
              (server/start-server
               :bind "127.0.0.1"
               :handler (server/default-handler #'cider.piggieback/wrap-cljs-repl))]
    (let [port (.getLocalPort ^java.net.ServerSocket (:server-socket server))
          conn (nrepl/connect :port port)
          session (nrepl/client-session (nrepl/client conn Long/MAX_VALUE))]
      (try
        (let [start (nrepl/combine-responses
                     (nrepl/message session {:op "eval" :code start-code}))]
          (testing "the setup really does error (otherwise the test proves nothing)"
            (is (some? (:err start)))))
        (testing "evaluation still works after the setup error"
          ;; Without the fix this throws an NPE in cljs.analyzer/get-namespace
          ;; (no compiler env), yielding an eval-error and no value.
          (let [resp (nrepl/combine-responses
                      (nrepl/message session {:op "eval" :code "(+ 1 1)"}))]
            (testing (pr-str resp)
              (is (= ["42"] (:value resp)))
              (is (not (contains? (:status resp) "eval-error"))))))
        (finally
          (dorun (nrepl/message session {:op "eval" :code ":cljs/quit"}))
          (.close ^java.io.Closeable conn))))))
