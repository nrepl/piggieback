(ns cider.piggieback-repl-parity-test
  "Piggieback reads and evaluates outside of `cljs.repl/repl*`'s loop, so it
  recreates the dynamic environment `repl*` sets up around it (see
  `cider.piggieback.cljs/eval-bindings`).

  These tests run the real `repl*` against a stub JavaScript env (no runtime
  needed), capture its thread bindings, and check that Piggieback binds the same
  vars to the same values. A ClojureScript release that changes what `repl*`
  binds makes them fail."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [cljs.repl]
   [cider.piggieback]
   [cider.piggieback.cljs :as core]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(defrecord StubEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [_ _])
  (-evaluate [_ _ _ _] {:status :success :value "nil"})
  (-load [_ _ _])
  (-tear-down [_]))

;; Non-default values, so that an ignored option shows up as a mismatch.
(def ^:private user-opts
  {:warnings {:fn-deprecated false}
   :warn-on-undeclared false
   :static-fns true
   :fn-invoke-direct true
   :checked-arrays :warn
   :repl-verbose true})

;; The options repl* adds for driving its own loop, plus the hooks run-repl*
;; passes it. Evaluation doesn't use any of them.
(def ^:private loop-opts
  #{:compiler-env :init :prompt :need-prompt :quit-prompt :bind-err :flush :read
    :eval :print :caught :reader :print-no-newline :source-map-inline})

(defn- bindings-added
  "The bindings in `inner` that aren't in `outer`, or bound to something else."
  [outer inner]
  (into {}
        (remove (fn [[v val]] (and (contains? outer v) (identical? val (outer v)))))
        inner))

(defn- run-repl*
  "Run `repl*` over a single form, returning the bindings it added around
  reading and around evaluating that form, and the options it evaluated with."
  [repl-env compiler-env]
  (let [captured (atom {})
        reads (atom 0)]
    ;; :repl-verbose makes repl* print the JavaScript it evaluates
    (binding [*out* (java.io.StringWriter.)
              *err* (java.io.StringWriter.)]
      (let [outer (get-thread-bindings)]
        (cljs.repl/repl*
         repl-env
         (merge user-opts
                {:compiler-env compiler-env
                 :init (fn [])
                 :prompt (fn [])
                 :need-prompt (constantly false)
                 :quit-prompt (fn [])
                 :bind-err false
                 :read (fn [_request-prompt request-exit]
                         (if (= 1 (swap! reads inc))
                           (do (swap! captured assoc :read (get-thread-bindings))
                               '(+ 1 2))
                           request-exit))
                 :eval (fn [_repl-env _env _form opts]
                         (swap! captured assoc
                                :eval (get-thread-bindings)
                                :opts opts)
                         "3")
                 :print (fn [& _])
                 :caught (fn [e & _] (throw e))}))
        (let [{:keys [read eval opts]} @captured]
          {:read (bindings-added eval read)
           :eval (bindings-added outer eval)
           :opts opts})))))

(defn- var-name
  "The var's name, minus the prefix of the ClojureScript-vendored libraries
  (newer `cljs.repl`s read with a vendored tools.reader, Piggieback with the
  regular one)."
  [v]
  (symbol (string/replace (str (symbol v)) #"^cljs\.vendor\." "")))

(defn- check-parity
  "Check that `ours` binds every var in `repl*` to the same value. Reports var
  names only, as the values include whole compiler envs."
  [repl* ours]
  (let [by-name #(into {} (map (fn [[v val]] [(var-name v) val])) %)
        repl* (by-name repl*)
        ours (by-name ours)]
    (is (empty? (remove #(contains? ours %) (keys repl*)))
        "vars bound by repl* but not by Piggieback")
    (is (empty? (for [[k v] repl*
                      :when (and (contains? ours k) (not= v (ours k)))]
                  k))
        "vars Piggieback binds to a different value than repl*")))

(deftest bindings-match-repl*
  (binding [ana/*cljs-ns* 'cljs.user]
    (let [repl-env (->StubEnv)
          compiler-env (env/default-compiler-env)
          ;; an :as-alias require, which only some ClojureScript versions'
          ;; alias maps include
          _ (swap! compiler-env update-in [::ana/namespaces 'cljs.user] merge
                   {:requires '{s clojure.string}
                    :as-aliases '{a some.aliased.ns}})
          {:keys [read eval opts]} (run-repl* repl-env compiler-env)
          pb-opts (core/build-opts (core/repl-options repl-env) user-opts)
          ours (core/eval-bindings compiler-env repl-env pb-opts)]
      (is (seq read))
      (is (seq eval))
      (testing "the options Piggieback evaluates with match repl*'s"
        (is (= pb-opts (apply dissoc opts loop-opts))))
      (testing "reading"
        (check-parity read
                      (env/with-compiler-env compiler-env
                        (core/read-bindings))))
      (testing "evaluating"
        ;; *repl-opts* holds the options compared above, plus repl*'s hooks.
        ;; The analyzer namespace and *in* are tracked in the session and per
        ;; message instead.
        (check-parity (dissoc eval #'cljs.repl/*repl-opts* #'ana/*cljs-ns* #'*in*)
                      ours)
        (is (= pb-opts (get ours #'cljs.repl/*repl-opts*)))))))

(def ^:private seen-warnings (atom []))

(defn record-warning [warning-type _env _extra]
  (swap! seen-warnings conj warning-type))

;; repl* computes its bindings once, from the dynamic environment it's started
;; in. Tools rely on that: figwheel-main installs its own warning handlers around
;; cljs-repl, and they must still be the ones in effect for later evaluations.
(deftest warning-handlers-from-repl-start-are-used
  (with-open [^nrepl.server.Server server
              (server/start-server
               :bind "127.0.0.1"
               :handler (server/default-handler #'cider.piggieback/wrap-cljs-repl))]
    (let [port (.getLocalPort ^java.net.ServerSocket (:server-socket server))
          session (-> (nrepl/connect :port port)
                      (nrepl/client Long/MAX_VALUE)
                      nrepl/client-session)]
      (try
        (dorun (nrepl/message
                session
                {:op "eval"
                 :code (nrepl/code
                        (binding [cljs.analyzer/*cljs-warning-handlers*
                                  [cider.piggieback-repl-parity-test/record-warning]]
                          (cider.piggieback/cljs-repl
                           (cider.piggieback-repl-parity-test/->StubEnv)
                           ;; keeps this compilation out of the shared "out" dir
                           :output-dir "target/piggieback-parity-out")))}))
        (reset! seen-warnings [])
        (dorun (nrepl/message session {:op "eval" :code "(when false undeclared-xyz)"}))
        (is (some #{:undeclared-var} @seen-warnings))
        (finally
          (dorun (nrepl/message session {:op "eval" :code ":cljs/quit"})))))))
