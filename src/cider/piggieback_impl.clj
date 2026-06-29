(in-ns 'cider.piggieback)

(require
 '[clojure.main]
 '[clojure.string :as string]
 '[clojure.tools.reader.edn :as edn-reader]
 '[cider.piggieback.cljs :as core]
 '[cider.piggieback.compat :as compat]
 '[nrepl.core :as nrepl]
 '[nrepl.middleware.interruptible-eval :as ieval]
 '[nrepl.misc :as misc :refer [response-for]]
 '[nrepl.transport :as transport])

(import
 '(java.io Writer))

;; this is the var that is checked by the middleware to determine whether an
;; active CLJS REPL is in flight
(def ^:private ^:dynamic *cljs-repl-env* nil)
(def ^:private ^:dynamic *cljs-compiler-env* nil)
(def ^:private ^:dynamic *cljs-repl-options* nil)
(def ^:private ^:dynamic *cljs-warnings* nil)
(def ^:private ^:dynamic *cljs-warning-handlers* nil)
(def ^:private ^:dynamic *original-clj-ns* nil)

;; Atoms holding the Writer that the ClojureScript repl env's output should be
;; forwarded to. They are repointed at the current message's output on every
;; evaluation, see `forwarding-writer` and issue #111 for the details.
(def ^:private ^:dynamic *cljs-out-target* nil)
(def ^:private ^:dynamic *cljs-err-target* nil)

(defn- forwarding-writer
  "Return a `java.io.Writer` that always delegates to the Writer currently held
  in `target` (an atom).

  ClojureScript repl envs (e.g. the Node env) capture `*out*` once, at setup
  time, on the thread that pumps the JS runtime's output back to the user. Bound
  via `bound-fn`, that thread keeps writing to the output of the message that
  *started* the REPL, so every later evaluation's output ends up with the wrong
  message id and vanishes entirely once that connection is closed (issue #111).
  Handing the env a forwarding writer lets us repoint it at the current
  message's output on each evaluation."
  ^java.io.Writer [target]
  (proxy [java.io.Writer] []
    ;; The proxy routes every `write` overload (and, via the superclass'
    ;; `append`, those too) through this fn, so we have to cover both the
    ;; single-argument and the (data, off, len) arities and dispatch on type.
    (write
      ([x]
       (let [^java.io.Writer w @target]
         (cond
           (integer? x) (.write w (int x))
           (string? x) (.write w ^String x)
           :else (.write w ^chars x))))
      ([data off len]
       (let [^java.io.Writer w @target]
         (if (string? data)
           (.write w ^String data (int off) (int len))
           (.write w ^chars data (int off) (int len))))))
    (flush [] (.flush ^java.io.Writer @target))
    ;; Deliberately a flush, not a close: the underlying per-message writers are
    ;; owned and closed by nREPL, we must not close them here.
    (close [] (.flush ^java.io.Writer @target))))

(deftype ^:private UnknownTaggedLiteral [tag data])

(defmethod print-method UnknownTaggedLiteral
  [^UnknownTaggedLiteral this ^java.io.Writer w]
  ;; Recurse through print-method (rather than str) so the data round-trips
  ;; correctly: strings keep their quotes, nested values are printed readably,
  ;; and the tag and data are separated by a space (issue #120).
  (.write w "#")
  (print-method (.tag this) w)
  (.write w " ")
  (print-method (.data this) w))

(defn repl-caught [session transport nrepl-msg err repl-env repl-options]
  (let [root-ex (#'clojure.main/root-cause err)]
    (when-not (instance? ThreadDeath root-ex)
      (set! *e err)
      (swap! session assoc #'*e err)
      (transport/send transport (response-for nrepl-msg {:status :eval-error
                                                         :ex (-> err class str)
                                                         :root-ex (-> root-ex class str)}))
      ((:caught repl-options core/default-caught) err repl-env repl-options))))

(defn- ensure-close-teardown!
  "Augment the session's `:close` metadata (once) so that closing the session
  also tears down any active ClojureScript repl-env.

  nREPL's session middleware handles the `close` op itself and never delegates
  it to Piggieback, so we can't intercept it as an op. Instead we compose our
  teardown into the `:close` fn that `nrepl.middleware.session/close-session`
  invokes. Without this, closing a session (or a client exiting) without first
  sending `:cljs/quit` leaks the JavaScript runtime, e.g. a Node subprocess."
  [session]
  (when-not (::close-hooked (meta session))
    (alter-meta!
     session
     (fn [m]
       (let [orig-close (:close m)]
         (assoc m
                ::close-hooked true
                :close (fn []
                         ;; Read the repl-env at close time so that a prior
                         ;; :cljs/quit (which nils it out) means we don't try to
                         ;; tear an already-torn-down runtime down again.
                         (try
                           (when-let [repl-env (@session #'*cljs-repl-env*)]
                             (core/tear-down! (core/get-repl-env repl-env)))
                           (catch Throwable _))
                         (when orig-close (orig-close)))))))))

;; This function always executes when the nREPL session is evaluating Clojure,
;; via interruptible-eval, etc. This means our dynamic environment is in place,
;; so set! and simple dereferencing is available. Contrast w/ evaluate and
;; load-file below.
(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session.  Accepts
   all options usually accepted by e.g. cljs.repl/repl."
  [repl-env & {:as options}]
  ;; cljs-repl stores its state with `set!` on dynamic vars that the nREPL
  ;; session middleware establishes per message. Called outside of a session -
  ;; e.g. from Leiningen's :repl-options :init, which runs at startup before any
  ;; session exists - those vars have no thread binding and `set!` fails with a
  ;; cryptic "Can't change/establish root binding" error (issue #124). Fail fast
  ;; with an actionable message instead.
  (when-not (thread-bound? #'*cljs-repl-env*)
    (throw (ex-info
            (str "cider.piggieback/cljs-repl must be invoked from within an nREPL "
                 "session, e.g. by evaluating it at a REPL connected to an nREPL "
                 "server that has the wrap-cljs-repl middleware. It cannot be "
                 "started from a plain Clojure REPL or from Leiningen's "
                 ":repl-options :init, both of which run outside of any session.")
            {:repl-env repl-env})))
  (try
    (let [repl-opts (core/repl-options repl-env)
          repl-env (core/delegating-repl-env repl-env)
          ;; have to initialise repl-options the same way they
          ;; are initilized inside of the cljs.repl/repl loop
          ;; because we are calling evaluate outside of the repl
          ;; loop.
          opts (core/build-opts repl-opts options)
          ;; Create the compiler env up front and hand it to the repl loop so we
          ;; always hold a reference to it, even if the setup eval errors and the
          ;; loop's :print callback (which would otherwise capture it) never runs
          ;; (issue #62).
          compiler-env (or (:compiler-env options) (core/default-compiler-env opts))
          {:keys [session ns]} ieval/*msg*
          init-ns (if ns (symbol ns) (get @session core/ns-var))]
      (core/set-current-ns! 'cljs.user)
      (let [out-target (atom *out*)
            err-target (atom *err*)]
        ;; Set up the repl env with forwarding writers in place so its
        ;; output-pump thread forwards to the current message's output rather
        ;; than to the one that started the REPL (issue #111).
        (binding [*out* (forwarding-writer out-target)
                  *err* (forwarding-writer err-target)]
          (core/setup-repl
           repl-env compiler-env options init-ns
           ;; this is needed to respect :repl-requires
           (if-let [requires (not-empty (:repl-requires opts))]
             (pr-str (cons 'ns `(cljs.user (:require ~@requires
                                                     [~'cljs.repl :refer-macros [~'source ~'doc ~'find-doc
                                                                                 ~'apropos ~'dir ~'pst]]
                                                     [~'cljs.pprint]))))
             (nrepl/code (ns cljs.user
                           (:require [cljs.repl :refer-macros [source doc find-doc
                                                               apropos dir pst]]
                                     [cljs.pprint]))))
           ;; implicitly records the compiler env and tracks the namespace
           (fn [result-ns result-compiler-env]
             (when (or (not ns) (not= init-ns result-ns))
               (swap! session assoc core/ns-var result-ns))
             (set! *cljs-compiler-env* result-compiler-env))))
        (set! *cljs-out-target* out-target)
        (set! *cljs-err-target* err-target))
      ;; Record the compiler env unconditionally, in case the setup eval errored
      ;; and the on-setup callback never set it (issue #62).
      (set! *cljs-compiler-env* compiler-env)
      (set! *cljs-repl-env* repl-env)
      (set! *cljs-repl-options* opts)
      ;; interruptible-eval is in charge of emitting the final :ns response in this context
      (set! *original-clj-ns* *ns*)
      (set! *cljs-warnings* (core/warnings))
      (set! *cljs-warning-handlers* (core/warning-handlers))
      (set! *ns* (find-ns (core/current-ns)))
      ;; make sure a leaked JS runtime is torn down if the session is closed
      ;; without a :cljs/quit first
      (ensure-close-teardown! session)
      (println "To quit, type:" :cljs/quit))
    (catch Exception e
      (set! *cljs-repl-env* nil)
      (throw e))))

(defn- enqueue [{:keys [id session transport] :as msg} func]
  (let [{:keys [exec]} (meta session)]
    ;; Binding *msg* outside of :exec is the correct way to pass msg in nREPL
    ;; 1.3+ (actually, the correct way is to pass msg as the fourth argument,
    ;; but binding *msg* works too). Binding it inside of `f` is needed in nREPL
    ;; <1.3. We do both to be compatible with either.
    (binding [ieval/*msg* msg]
      (exec id
            #(binding [ieval/*msg* msg]
               (func))
            #(transport/send transport (response-for msg :status :done))))))

(defn read-cljs-string [form-str]
  (core/read-form form-str))

(defn eval-cljs [repl-env env form file opts]
  (core/eval-form repl-env env form file opts (::print opts)))

(defn do-eval [{:keys [session transport ^String code file ns] :as msg}]
  (with-bindings (merge (core/eval-bindings (get @session #'*cljs-compiler-env*)
                                            (get @session #'*cljs-repl-env*))
                        ;; On nREPL 1.3+ the session middleware already binds the
                        ;; session contents, so we must not rebind them here.
                        (when-not compat/nrepl-1-3+?
                          @session)
                        (when ns
                          {core/ns-var (symbol ns)})
                        (compat/output-bindings msg))
    ;; Repoint the repl env's output-pump thread at this message's output (#111).
    (when *cljs-out-target* (reset! *cljs-out-target* *out*))
    (when *cljs-err-target* (reset! *cljs-err-target* *err*))
    (let [repl-env *cljs-repl-env*
          repl-options *cljs-repl-options*
          init-ns (core/current-ns)
          special-fns (core/special-fns repl-options)
          is-special-fn? (set (keys special-fns))]
      (try
        (let [form (read-cljs-string code)
              env  (core/analyzer-env init-ns)
              result (when form
                       (if (and (seq? form) (is-special-fn? (first form)))
                         (do ((get special-fns (first form)) repl-env env form repl-options)
                             nil)
                         (eval-cljs repl-env
                                    env
                                    form
                                    file
                                    (assoc repl-options
                                           ::print
                                           (:nrepl.middleware.print/print msg)))))]
          (.flush ^Writer *out*)
          (.flush ^Writer *err*)
          (when (and (or (not ns)
                         (not= init-ns (core/current-ns)))
                     (core/current-ns))
            (swap! session assoc core/ns-var (core/current-ns)))
          (transport/send
           transport
           (response-for msg
                         (try
                           {:value (when (some? result)
                                     (edn-reader/read-string
                                      {:default ->UnknownTaggedLiteral}
                                      result))
                            :nrepl.middleware.print/keys #{:value}
                            :ns (core/current-ns)}
                           (catch Exception _
                             {:value (or result "nil")
                              :ns (core/current-ns)})))))
        (catch Throwable t
          (repl-caught session transport msg t repl-env repl-options))))))

;; only executed within the context of an nREPL session having *cljs-repl-env*
;; bound. Thus, we're not going through interruptible-eval, and the user's
;; Clojure session (dynamic environment) is not in place, so we need to go
;; through the `session` atom to access/update its vars. Same goes for load-file.
(defn- evaluate [{:keys [session transport ^String code] :as msg}]
  (if-not (-> code string/trim (string/ends-with? ":cljs/quit"))
    (do-eval msg)

    (let [actual-repl-env (core/get-repl-env (@session #'*cljs-repl-env*))
          orig-ns (@session #'*original-clj-ns*)]
      (core/tear-down! actual-repl-env)
      (swap! session assoc
             #'*ns* orig-ns
             #'*cljs-repl-env* nil
             #'*cljs-compiler-env* nil
             #'*cljs-repl-options* nil
             core/ns-var 'cljs.user)
      (when (thread-bound? #'*ns*)
        (set! *ns* orig-ns))
      (transport/send transport (response-for msg
                                              :value "nil"
                                              :ns (str orig-ns))))))

(defn- do-load-file
  "Evaluate the ClojureScript source sent in the `load-file` message (its
  `:file`), against the active repl-env. Mirrors the binding setup of `do-eval`."
  [{:keys [session transport file file-path file-name] :as msg}]
  (with-bindings (merge (core/eval-bindings (get @session #'*cljs-compiler-env*)
                                            (get @session #'*cljs-repl-env*))
                        ;; On nREPL 1.3+ the session middleware already binds the
                        ;; session contents, so we must not rebind them here.
                        (when-not compat/nrepl-1-3+?
                          @session)
                        (compat/output-bindings msg))
    ;; Repoint the repl env's output-pump thread at this message's output (#111).
    (when *cljs-out-target* (reset! *cljs-out-target* *out*))
    (when *cljs-err-target* (reset! *cljs-err-target* *err*))
    (let [repl-env *cljs-repl-env*
          repl-options *cljs-repl-options*]
      (try
        (core/load-source repl-env file (or file-path file-name "<cljs file>"))
        (.flush ^Writer *out*)
        (.flush ^Writer *err*)
        (transport/send transport (response-for msg
                                                :value "nil"
                                                :ns (str (core/current-ns))))
        (catch Throwable t
          (repl-caught session transport msg t repl-env repl-options))))))

(defn- load-file [{:keys [file file-path] :as msg}]
  (if (string? file)
    ;; Evaluate the source sent in the message, so unsaved buffers load
    ;; correctly (matching nREPL's behaviour on Clojure).
    (do-load-file msg)
    ;; No content was sent: fall back to loading the file from disk via the cljs
    ;; `load-file` special function.
    (evaluate (assoc msg :code (format "(load-file %s)" (pr-str file-path))))))

(defn describe-cljs
  "A describe-fn (see nREPL's `wrap-describe`) that contributes Piggieback's
  per-session ClojureScript status to the `describe` response's `:aux` map.

  Lets tooling detect that Piggieback is present and whether the session is
  currently evaluating ClojureScript (and against which repl-env), instead of
  having to infer it out of band."
  [{:keys [session]}]
  (let [repl-env (when session (get @session #'*cljs-repl-env*))]
    {:piggieback (cond-> {:cljs-repl (if repl-env "active" "inactive")}
                   repl-env (assoc :repl-env-type
                                   (.getName (class (core/get-repl-env repl-env)))))}))

(defn wrap-cljs-repl [handler]
  (fn [{:keys [session op] :as msg}]
    (let [handler (or (when-let [f (and (@session #'*cljs-repl-env*)
                                        ({"eval" #'evaluate "load-file" #'load-file} op))]
                        (fn [msg]
                          (enqueue msg #(f msg))))
                      handler)]
      ;; ensure that bindings exist so cljs-repl can set!
      (when-not (@session #'*cljs-repl-env*)
        (swap! session (partial merge {#'*cljs-repl-env* *cljs-repl-env*
                                       #'*cljs-compiler-env* *cljs-compiler-env*
                                       #'*cljs-repl-options* *cljs-repl-options*
                                       #'*cljs-warnings* *cljs-warnings*
                                       #'*cljs-warning-handlers* *cljs-warning-handlers*
                                       #'*cljs-out-target* *cljs-out-target*
                                       #'*cljs-err-target* *cljs-err-target*
                                       #'*original-clj-ns* *ns*
                                       core/ns-var (core/current-ns)})))
      (handler msg))))
