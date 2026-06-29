(ns cider.piggieback-test
  (:require
   [clojure.java.shell]
   [clojure.test :refer [deftest is use-fixtures testing]]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(require '[cider.piggieback :as pb])

(def ^:dynamic *server-port* nil)
(def ^:dynamic *session*)

(def ^:private cljs-repl-start-code
  (do (require 'cljs.repl.node)
      (nrepl/code
       (cider.piggieback/cljs-repl
        (cljs.repl.node/repl-env)))))

(defn repl-server-fixture
  [f]
  (let [{:keys [exit]
         :as v} (clojure.java.shell/sh "node" "--version")]
    (assert (zero? exit)
            (pr-str v)))

  (with-open [^nrepl.server.Server
              server (server/start-server
                      :bind "127.0.0.1"
                      :handler (server/default-handler #'cider.piggieback/wrap-cljs-repl))]
    (let [port (.getLocalPort ^java.net.ServerSocket (:server-socket server))
          conn (nrepl/connect :port port)
          session (nrepl/client-session (nrepl/client conn Long/MAX_VALUE))]
      ;; need to let the dynamic bindings get in place before trying to eval anything that
      ;; depends upon those bindings being set
      (dorun (nrepl/message session {:op "eval" :code cljs-repl-start-code}))
      (try
        (binding [*server-port* port
                  *session* session]
          (f))
        (finally
          (dorun (nrepl/message session {:op "eval" :code ":cljs/quit"})))))))

(use-fixtures :once repl-server-fixture)

(deftest default-sanity
  (dorun (nrepl/message *session* {:op "eval" :code "(defn x [] (into [] (js/Array 1 2 3)))"}))
  (is (= [1 2 3] (->> {:op "eval" :code "(x)"}
                      (nrepl/message *session*)
                      nrepl/response-values
                      first))))

(deftest proper-ns-tracking
  (let [response (-> (nrepl/message *session* {:op "eval" :code "5"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["5"] (:value response)))
      (is (= "cljs.user" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns foo.bar)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["nil"] (:value response)))
      (is (= "foo.bar" (:ns response)))))

  (dorun (nrepl/message *session* {:op "eval" :code "(defn ns-tracking [] (into [] (js/Array 1 2 3)))"}))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns-tracking)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["[1 2 3]"] (:value response)))
      (is (= "foo.bar" (:ns response)))))

  ;; TODO emit a response message to in-ns, doesn't seem to hit eval....
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(in-ns 'cljs.user)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= "cljs.user" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns cljs.user)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= "cljs.user" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns-tracking)" :ns "foo.bar"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["[1 2 3]"] (:value response)))
      (is (= "foo.bar" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns foo.bar)" :ns "cljs.user"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= "foo.bar" (:ns response)))))

  ;; verifying that this doesn't throw
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(require 'hello-world.foo :reload)" :ns "foo.bar"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (:value response))
      (is (= "foo.bar" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(in-ns 'cljs.user)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (is (= "cljs.user" (:ns response))))))

;; Piggieback contributes its per-session ClojureScript status to nREPL's
;; `describe` response, so tooling can detect cljs mode from the protocol rather
;; than inferring it. The fixture has an active node REPL, so describe should
;; report it as such.
(deftest describe-surfaces-cljs-state
  (let [response (-> (nrepl/message *session* {:op "describe"})
                     nrepl/combine-responses)
        pb (get-in response [:aux :piggieback])]
    (testing (pr-str response)
      (is (= "active" (:cljs-repl pb)))
      (is (= "cljs.repl.node.NodeEnv" (:repl-env-type pb))))))

;; The forwarding writer stands in for *out*/*err* while the repl env is set up,
;; so it must cope with every way Clojure and the repl env write to it, not just
;; the (char[], off, len) arity the Node output pump happens to use.
(deftest forwarding-writer-handles-all-write-arities
  (let [sink (java.io.StringWriter.)
        ^java.io.Writer fw (#'cider.piggieback/forwarding-writer (atom sink))]
    (.write fw (int \A))
    (.write fw "bc")
    (.write fw (char-array "de"))
    (.write fw "XfgY" 1 2)
    (.append fw \h)
    (.append fw "ij")
    (binding [*out* fw] (print "k") (pr {:l 1}) (flush))
    (is (= "Abcdefghijk{:l 1}" (str sink)))))

;; Regression test for https://github.com/nrepl/piggieback/issues/111
;;
;; ClojureScript output used to be tagged with the message that started the REPL
;; instead of the message that produced it, so `nrepl/message` (which filters by
;; id) never saw it, and it vanished entirely once that connection was closed.
;;
;; The reconnection case is exercised over a fresh connection to the SAME server
;; and session as the fixture; spinning up a second `cljs.repl.node` REPL in the
;; same JVM is not an option, as the Node env keys its eval state on a global
;; that collapses all nREPL threads together.
(deftest output-routing
  (testing "output arrives associated with the evaluating message, not the REPL-starting one"
    (let [response (-> (nrepl/message *session* {:op "eval" :code "(println \"hey\")"})
                       nrepl/combine-responses)]
      (testing (pr-str response)
        (is (= "hey\n" (:out response))))))

  (testing "output still arrives after reconnecting to the session on a new connection"
    (let [sess-id (-> (nrepl/message *session* {:op "eval" :code "1"}) first :session)]
      (with-open [^java.io.Closeable conn (nrepl/connect :port *server-port*)]
        (let [session (nrepl/client-session (nrepl/client conn Long/MAX_VALUE)
                                            :session sess-id)
              response (-> (nrepl/message session {:op "eval" :code "(println \"reconnected\")"})
                           nrepl/combine-responses)]
          (testing (pr-str response)
            (is (= "reconnected\n" (:out response)))))))))
