(ns cider.piggieback-test
  (:require
   [clojure.java.shell]
   [clojure.test :refer [deftest is use-fixtures testing]]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(require '[cider.piggieback :as pb])

(def ^:dynamic *server-port* nil)
(def ^:dynamic *session*)

(defn assert-exit-ns [session ns]
  (let [v (keep :ns (nrepl/message
                     session
                     {:op "eval" :code "clojure.core/*ns*"}))]
    (assert (= ["user"] v)
            (pr-str v))))

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
          (dorun (nrepl/message session {:op "eval" :code ":cljs/quit"}))
          (assert-exit-ns session "user"))))))

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
      (is (= "cljs.user" (:ns response)))))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns foo.bar)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= "foo.bar" (:ns response)))))

  (dorun (nrepl/message *session* {:op "eval" :code "(defn ns-tracking [] (into [] (js/Array 1 2 3)))"}))

  (let [response (-> (nrepl/message *session* {:op "eval" :code "(ns-tracking)"})
                     nrepl/combine-responses)]
    (testing (pr-str response)
      (some-> response :err println)
      (is (= ["[1 2 3]"] (:value response)))))

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
      (is (= "cljs.user" (:ns response)))))

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
      (is (:value response)))))
