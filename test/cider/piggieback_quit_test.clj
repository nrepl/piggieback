(ns cider.piggieback-quit-test
  "Regression test: `:cljs/quit` must tear down the ClojureScript runtime and
  return the session to Clojure. On nREPL 1.3+ the session stayed in
  ClojureScript mode, so the next evaluation went to the torn-down runtime.

  Node-free: a stub env stands in for a real one, as a second live Node REPL
  can't run alongside the main fixture (cljs.repl.node keeps global state)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cider.piggieback]
   [cljs.repl]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(def ^:private torn-down? (atom false))

(defrecord StubEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [_ _])
  (-evaluate [_ _ _ _] {:status :success :value "\"from cljs\""})
  (-load [_ _ _])
  (-tear-down [_] (reset! torn-down? true)))

(defn- eval-code [session code]
  (nrepl/combine-responses (nrepl/message session {:op "eval" :code code})))

(deftest quit-returns-the-session-to-clojure
  (with-open [^nrepl.server.Server server
              (server/start-server
               :bind "127.0.0.1"
               :handler (server/default-handler #'cider.piggieback/wrap-cljs-repl))]
    (let [port (.getLocalPort ^java.net.ServerSocket (:server-socket server))
          session (-> (nrepl/connect :port port)
                      (nrepl/client Long/MAX_VALUE)
                      nrepl/client-session)]
      (reset! torn-down? false)
      (eval-code session (nrepl/code
                          (cider.piggieback/cljs-repl
                           (cider.piggieback-quit-test/->StubEnv)
                           ;; keeps this compilation out of the shared "out" dir
                           :output-dir "target/piggieback-quit-out")))
      (testing "the session is in ClojureScript mode"
        (is (= ["\"from cljs\""] (:value (eval-code session "(+ 1 2)")))))
      (let [quit (eval-code session ":cljs/quit")]
        (testing (pr-str quit)
          (is (true? @torn-down?))
          (is (= "user" (:ns quit)))))
      (testing "evaluation is back to Clojure"
        (let [response (eval-code session "(+ 1 2)")]
          (is (= ["3"] (:value response)))
          (is (= "user" (:ns response)))))
      (testing "and stays there"
        (is (= ["3"] (:value (eval-code session "(+ 1 2)"))))))))
