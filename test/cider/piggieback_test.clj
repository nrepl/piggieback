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

;; A throwing evaluation must be reported as an eval error (this exercises the
;; delegating repl-env's error-formatting protocols, since cljs.repl's
;; display-error reaches for them), and the session must keep working afterwards.
(deftest eval-error-is-reported-and-recoverable
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(throw (js/Error. \"boom\"))"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (is (contains? (:status response) "eval-error"))))
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(+ 1 1)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["2"] (:value response))))))

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

;; load-file must evaluate the source *sent in the message* (an editor buffer,
;; possibly unsaved), not re-read the file from disk. Here the file path points
;; nowhere on disk, so this only passes if the content is what gets loaded.
(deftest load-file-evaluates-sent-content
  (let [content (nrepl/code
                 (ns piggieback.load-test)
                 (defn answer [] 42))
        response (-> (nrepl/message *session*
                                    {:op "load-file" :file content
                                     :file-path "nonexistent/piggieback/load_test.cljs"
                                     :file-name "load_test.cljs"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (contains? (:status response) "done"))
      (is (not (contains? (:status response) "eval-error")))))
  ;; the namespace and var defined by the loaded buffer are now available
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(piggieback.load-test/answer)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["42"] (:value response))))))

;; Regression test for https://github.com/nrepl/piggieback/issues/154
;; Loading a file whose ns requires a foreign lib (e.g. a cljsjs package) failed
;; with "No such namespace", as the forms were evaluated without the repl options
;; that tell the compiler about the libs declared in deps.cljs files.
(deftest load-file-with-foreign-lib-dependency
  (let [content (nrepl/code
                 (ns piggieback.foreign-load-test
                   (:require [piggieback-test.foreign-lib]))
                 (defn answer [] (.answer js/piggiebackForeignLib)))
        response (-> (nrepl/message *session*
                                    {:op "load-file" :file content
                                     :file-path "nonexistent/piggieback/foreign_load_test.cljs"
                                     :file-name "foreign_load_test.cljs"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (contains? (:status response) "done"))
      (is (not (contains? (:status response) "eval-error")))))
  (let [response (-> (nrepl/message *session* {:op "eval" :code "(piggieback.foreign-load-test/answer)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["42"] (:value response))))))

;; The analyzer `set!`s these when they're `set!` in ClojureScript code, which
;; needs a thread binding for each of them. As in cljs.repl, the change lasts
;; for the rest of the REPL session, except in a loaded file, where it lasts
;; until the end of the file.
(deftest set!-analyzer-vars
  (let [settings '[*unchecked-if* *unchecked-arrays* *warn-on-infer*]
        eval-code #(-> (nrepl/message *session* {:op "eval" :code %})
                       nrepl/combine-responses)
        ;; with *unchecked-if*, `if` uses JavaScript truthiness, where 0 is false
        zero-test "(let [zero 0] (if zero :truthy :falsy))"]
    (try
      (doseq [setting settings
              :let [code (format "(set! %s true)" setting)
                    response (eval-code code)]]
        (testing code
          (some-> response :err println)
          (is (= ["true"] (:value response)))
          (is (not (contains? (:status response) "eval-error")))))
      (testing "a set! in an eval lasts"
        (is (= [":falsy"] (:value (eval-code zero-test)))))
      (eval-code "(set! *unchecked-if* false)")
      (dorun (nrepl/message *session*
                            {:op "load-file"
                             :file (str "(set! *unchecked-if* true) (def zero-in-file " zero-test ")")
                             :file-path "nonexistent/piggieback/unchecked.cljs"}))
      (testing "a set! in a loaded file applies to the rest of the file"
        (is (= [":falsy"] (:value (eval-code "zero-in-file")))))
      (testing "but not beyond it"
        (is (= [":truthy"] (:value (eval-code zero-test)))))
      (finally
        (doseq [setting settings]
          (eval-code (format "(set! %s false)" setting)))))))

;; Keywords qualified with an :as-alias alias must read, as they do in
;; ClojureScript's own REPL. :as-alias needs ClojureScript 1.11+.
(deftest as-alias-keywords
  (when (resolve 'cljs.analyzer/get-aliases)
    (try
      (dorun (nrepl/message *session* {:op "eval"
                                       :code "(ns piggieback.as-alias-test (:require [piggieback.not-loaded :as-alias nl]))"}))
      (let [response (-> (nrepl/message *session* {:op "eval" :code "::nl/kw"})
                         nrepl/combine-responses)]
        (testing (pr-str response)
          (some-> response :err println)
          (is (= [":piggieback.not-loaded/kw"] (:value response)))))
      (finally
        (dorun (nrepl/message *session* {:op "eval" :code "(in-ns 'cljs.user)"}))))))

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
