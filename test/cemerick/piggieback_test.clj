(ns cemerick.piggieback-test
  (:require [cemerick.piggieback :as pb]
            [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [server :as server]))
  (:use clojure.test))

(def ^:dynamic *server-port* nil)
(def ^:dynamic *session*)

(defn assert-exit-ns [session ns]
  (assert (= ["user"]
             (filter identity
                     (map :ns (nrepl/message
                                session
                                {:op "eval" :code "clojure.core/*ns*"}))))))

(def ^:private jdk8+ (try (import 'java.time.Instant)
                          true
                          (catch Exception _ false)))

(def ^:private cljs-repl-start-code
  (if jdk8+
    (do (require 'cljs.repl.nashorn)
        (nrepl/code
          (cemerick.piggieback/cljs-repl
            (cljs.repl.rhino/repl-env))))
    (nrepl/code
      (cemerick.piggieback/cljs-repl
        (cljs.repl.rhino/repl-env)))))

(defn repl-server-fixture
  [f]
  (with-open [server (server/start-server
                       :handler (server/default-handler #'cemerick.piggieback/wrap-cljs-repl))]
    (let [port (.getLocalPort (:ss @server))
          conn (nrepl/connect :port port)
          session (nrepl/client-session (nrepl/client conn Long/MAX_VALUE))]
      ; need to let the dynamic bindings get in place before trying to eval anything that
      ; depends upon those bingings being set
      (doall (nrepl/message session {:op "eval" :code cljs-repl-start-code}))
      (try
        (binding [*server-port* port
                  *session* session]
        (f))
        (finally
          (doall (nrepl/message session {:op "eval" :code ":cljs/quit"}))
          (assert-exit-ns session "user"))))))

(use-fixtures :once repl-server-fixture)

(deftest default-sanity
  ; I think there's a race condition between when previous expressions are evaluated
  ; (which nREPL serializes for a session) and when the next code to be evaluated is analyzed
  ; in piggieback (which has no visibility into the session serialization mechanism). The
  ; fix is to work like a REPL _should_, i.e. wait for the full response of an evaluation
  ; prior to sending out another chunk of code.
  (doall (nrepl/message *session* {:op "eval" :code "(defn x [] (into [] (js/Array 1 2 3)))"}))
  (is (= [1 2 3] (->> {:op "eval" :code "(x)"} (nrepl/message *session*) nrepl/response-values first))))

(deftest printing-works
  (is (= {:value ["nil"] :out "[1 2 3 4]\n"}
        (-> (nrepl/message *session* {:op "eval" :code "(println [1 2 3 4])"})
          nrepl/combine-responses
          (select-keys [:value :out])))))

(deftest proper-ns-tracking
  (is (= "cljs.user" (-> (nrepl/message *session* {:op "eval" :code "5"})
                       nrepl/combine-responses
                       :ns)))
  (is (= "foo.bar" (-> (nrepl/message *session* {:op "eval" :code "(ns foo.bar)"})
                       nrepl/combine-responses
                       :ns)))
  (doall (nrepl/message *session* {:op "eval" :code "(defn ns-tracking [] (into [] (js/Array 1 2 3)))"}))
  
  (is (= ["[1 2 3]"] (-> (nrepl/message *session* {:op "eval" :code "(ns-tracking)"})
                       nrepl/combine-responses
                       :value)))
  
  ;; TODO emit a response message to in-ns, doesn't seem to hit eval....
  (is (= "cljs.user" (-> (nrepl/message *session* {:op "eval" :code "(in-ns 'cljs.user)"})
                       nrepl/combine-responses
                       :ns)))
  (is (= "cljs.user" (-> (nrepl/message *session* {:op "eval" :code "(ns cljs.user)"})
                       nrepl/combine-responses
                       :ns)))
  (let [resp (-> (nrepl/message *session* {:op "eval" :code "(ns-tracking)" :ns "foo.bar"})
               nrepl/combine-responses)]
    (is (= ["[1 2 3]"] (:value resp)))
    (is (= "cljs.user" (:ns resp))))
  
  (is (= "foo.bar" (-> (nrepl/message *session* {:op "eval" :code "(ns foo.bar)" :ns "cljs.user"})
                     nrepl/combine-responses
                     :ns))))
