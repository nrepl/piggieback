(ns cider.piggieback.cljs
  "The ClojureScript implementation of Piggieback's nREPL middleware.

  Loaded lazily by `cider.piggieback`, and only when ClojureScript is on the
  classpath. All of Piggieback's coupling to the ClojureScript compiler
  internals (`cljs.repl`, `cljs.analyzer`, `cljs.env`, `cljs.closure`,
  `cljs.tagged-literals`) lives here, so the public `cider.piggieback` namespace
  carries no hard dependency on ClojureScript.

  The session-state dynamic vars and two pure helpers (`forwarding-writer`,
  `UnknownTaggedLiteral`) live in `cider.piggieback`, referred to here as `pb`."
  (:refer-clojure :exclude [load-file])
  (:require
   [clojure.main]
   [clojure.string :as string]
   [clojure.tools.reader :as reader]
   [clojure.tools.reader.edn :as edn-reader]
   [clojure.tools.reader.reader-types :as readers]
   [cljs.closure]
   [cljs.repl]
   [cljs.env :as env]
   [cljs.analyzer :as ana]
   [cljs.tagged-literals :as tags]
   [cider.piggieback :as pb]
   [cider.piggieback.compat :as compat]
   [nrepl.core :as nrepl]
   [nrepl.middleware.interruptible-eval :as ieval]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport])
  (:import
   (java.io StringReader Writer)))

;; ---------------------------------------------------------------------------
;; Analyzer / compiler state
;; ---------------------------------------------------------------------------

(def ns-var
  "The ClojureScript analyzer's current-namespace var. Exposed so the handlers
  can use it as a key in `binding` maps and in the session atom without naming a
  compiler internal directly."
  #'ana/*cljs-ns*)

(defn current-ns
  "The current ClojureScript namespace (a symbol)."
  []
  ana/*cljs-ns*)

(defn set-current-ns!
  "Set the current ClojureScript namespace. Must run on a thread where
  `ns-var` is bound."
  [ns-sym]
  (set! ana/*cljs-ns* ns-sym))

(defn warnings [] ana/*cljs-warnings*)

(defn warning-handlers [] ana/*cljs-warning-handlers*)

(defn eval-bindings
  "Binding map of the ClojureScript analyzer/compiler dynamic vars needed for a
  single evaluation. `compiler-env` and `repl-env` come from the session."
  [compiler-env repl-env]
  {#'ana/*cljs-warnings* ana/*cljs-warnings*
   #'ana/*cljs-warning-handlers* ana/*cljs-warning-handlers*
   #'ana/*unchecked-if* ana/*unchecked-if*
   #'env/*compiler* compiler-env
   #'cljs.repl/*repl-env* repl-env})

(defn analyzer-env
  "An empty analyzer environment scoped to `ns-sym`."
  [ns-sym]
  (assoc (ana/empty-env) :ns (ana/get-namespace ns-sym)))

;; ---------------------------------------------------------------------------
;; REPL options and compiler env
;; ---------------------------------------------------------------------------

(defn repl-options
  "The repl-env's own repl options (`cljs.repl/-repl-options`)."
  [repl-env]
  (cljs.repl/-repl-options repl-env))

(defn build-opts
  "Initialise the repl options the way `cljs.repl/repl*` would internally. We
  evaluate outside of that loop, so we have to do this ourselves."
  [repl-opts options]
  (merge
   {:def-emits-var true}
   (cljs.closure/add-implicit-options
    (merge-with (fn [a b] (if (nil? b) a b))
                repl-opts options))))

(defn default-compiler-env
  "A fresh ClojureScript compiler environment for `opts`."
  [opts]
  (env/default-compiler-env opts))

(defn special-fns
  "The REPL special functions, merging the cljs defaults with any provided by
  the repl options."
  [repl-options]
  (merge cljs.repl/default-special-fns (:special-fns repl-options)))

(def default-caught
  "The default `:caught` handler (`cljs.repl/repl-caught`)."
  cljs.repl/repl-caught)

;; ---------------------------------------------------------------------------
;; Delegating repl env
;; ---------------------------------------------------------------------------

;; We wrap the user's repl-env in a delegating env whose only behavioural
;; difference is a no-op `-tear-down`, so that driving `cljs.repl/repl*` for
;; setup (which tears the env down when its loop exits) doesn't kill the runtime
;; we want to keep around for subsequent evaluations.

(defprotocol GetReplEnv
  (get-repl-env [this]))

(def ^:private cljs-repl-protocol-impls
  {cljs.repl/IReplEnvOptions
   {:-repl-options (fn [repl-env] (cljs.repl/-repl-options (get-repl-env repl-env)))}
   cljs.repl/IParseError
   {:-parse-error (fn [repl-env err build-options]
                    (cljs.repl/-parse-error (get-repl-env repl-env) err build-options))}
   cljs.repl/IGetError
   {:-get-error (fn [repl-env name env build-options]
                  (cljs.repl/-get-error (get-repl-env repl-env) name env build-options))}
   cljs.repl/IParseStacktrace
   {:-parse-stacktrace (fn [repl-env stacktrace err build-options]
                         (cljs.repl/-parse-stacktrace (get-repl-env repl-env) stacktrace err build-options))}
   cljs.repl/IPrintStacktrace
   {:-print-stacktrace (fn [repl-env stacktrace err build-options]
                         (cljs.repl/-print-stacktrace (get-repl-env repl-env) stacktrace err build-options))}})

(defn- generate-delegating-repl-env [repl-env]
  (let [repl-env-class (class repl-env)
        classname (string/replace (.getName repl-env-class) \. \_)
        dclassname (str "Delegating" classname)]
    (eval
     (list*
      'deftype (symbol dclassname)
      '([repl-env]
        cider.piggieback.cljs/GetReplEnv
        (get-repl-env [this] (.-repl-env this))
        cljs.repl/IJavaScriptEnv
        (-setup [this options] (cljs.repl/-setup repl-env options))
        (-evaluate [this a b c] (cljs.repl/-evaluate repl-env a b c))
        (-load [this ns url] (cljs.repl/-load repl-env ns url))
        ;; This is the whole reason we are creating this delegator
        ;; to prevent the call to tear-down
        (-tear-down [_])
        clojure.lang.ILookup
        (valAt [_ k] (get repl-env k))
        (valAt [_ k default] (get repl-env k default))
        clojure.lang.Seqable
        (seq [_] (seq repl-env))
        clojure.lang.Associative
        (containsKey [_ k] (contains? repl-env k))
        (entryAt [_ k] (find repl-env k))
        (assoc [_ k v] (#'cider.piggieback.cljs/delegating-repl-env (assoc repl-env k v)))
        clojure.lang.IPersistentCollection
        (count [_] (count repl-env))
        (cons [_ entry] (conj repl-env entry))
        ;; pretty meaningless; most REPL envs are records for the assoc'ing, but they're not values
        (equiv [_ other] false))))
    (let [dclass (resolve (symbol dclassname))
          ctor (resolve (symbol (str "->" dclassname)))]
      (doseq [[protocol fn-map] cljs-repl-protocol-impls]
        (when (satisfies? protocol repl-env)
          (extend dclass protocol fn-map)))
      @ctor)))

(defn delegating-repl-env
  "Wrap `repl-env` in a delegating env with a no-op `-tear-down`."
  [repl-env]
  (let [ctor (generate-delegating-repl-env repl-env)]
    (ctor repl-env)))

(defn tear-down!
  "Tear down the (unwrapped) repl env."
  [repl-env]
  (cljs.repl/-tear-down repl-env))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn read-form
  "Read a single ClojureScript form from `form-str`, with the cljs data readers
  and the current namespace's alias map in place. Returns nil for blank input."
  [form-str]
  (when-not (string/blank? form-str)
    (binding [*ns* (create-ns ana/*cljs-ns*)
              reader/resolve-symbol ana/resolve-symbol
              reader/*data-readers* tags/*cljs-data-readers*
              reader/*alias-map*
              (apply merge
                     ((juxt :requires :require-macros)
                      (ana/get-namespace ana/*cljs-ns*)))]
      (reader/read {:read-cond :allow :features #{:cljs}}
                   (readers/source-logging-push-back-reader
                    (StringReader. form-str))))))

;; ---------------------------------------------------------------------------
;; Result wrapping and evaluation
;; ---------------------------------------------------------------------------

(defn- wrap-pprint
  "Wraps sexp with cljs.pprint/pprint in order for it to return a
  pretty-printed evaluation result as a string."
  [form]
  `(let [sb# (goog.string.StringBuffer.)
         sbw# (cljs.core/StringBufferWriter. sb#)
         form# ~form]
     (cljs.pprint/pprint form# sbw#)
     (cljs.core/str sb#)))

(defn- pprint-repl-wrap-fn [form]
  (cond
    (and (seq? form)
         (#{'ns 'require 'require-macros
            'use 'use-macros 'import 'refer-clojure} (first form)))
    identity

    ('#{*1 *2 *3 *e} form) (fn [x]
                             (wrap-pprint x))
    :else
    (fn [x]
      `(try
         ~(wrap-pprint
           `(let [ret# ~x]
              (set! *3 *2)
              (set! *2 *1)
              (set! *1 ret#)
              ret#))
         (catch :default e#
           (set! *e e#)
           (throw e#))))))

(defn eval-form
  "Evaluate a single ClojureScript `form` in `repl-env`/`env`. `opts` are the
  repl options; `print-fn` is the name of nREPL's requested print function, used
  to pick a result-wrapping strategy."
  [repl-env env form file opts print-fn]
  (cljs.repl/evaluate-form
   repl-env
   env
   (or file "<cljs repl>")
   form
   ((:wrap opts
           (if (contains? #{"nrepl.util.print/pr" "cider.nrepl.pprint/pr"} print-fn)
             #'cljs.repl/wrap-fn
             #'pprint-repl-wrap-fn))
    form)
   opts))

(defn load-source
  "Load ClojureScript `source` (a string of one or more forms) as if it were the
  file at `filename`, evaluating each form in `repl-env`.

  Unlike `cljs.repl/load-file`, this loads the source handed to it rather than
  reading the file from disk, so unsaved editor buffers load correctly. The
  current analyzer namespace is restored afterwards, matching the behaviour of
  `cljs.repl/load-file`."
  [repl-env source filename]
  (binding [ana/*cljs-ns* ana/*cljs-ns*]
    (cljs.repl/load-stream repl-env filename (StringReader. source))))

;; ---------------------------------------------------------------------------
;; REPL setup
;; ---------------------------------------------------------------------------

(defn setup-repl
  "Drive `cljs.repl/repl*` through a single setup form (the `cljs.user`
  namespace require), starting from analyzer namespace `init-ns`. After the
  setup eval, `on-setup` is called with the resulting analyzer namespace and the
  compiler env, so the caller can persist them.

  This is the one place we drive the full `repl*` loop rather than evaluating
  forms ourselves; everything else goes through `eval-form`."
  [repl-env compiler-env options init-ns code on-setup]
  (binding [ana/*cljs-ns* init-ns]
    (with-in-str (str code " :cljs/quit")
      (cljs.repl/repl*
       repl-env
       (merge
        {:compiler-env compiler-env}
        ;; if options has a compiler env let it override
        options
        ;; these options need to be set to the following values
        ;; for the repl to initialize correctly
        {:need-prompt (fn [])
         :init (fn [])
         :prompt (fn [])
         :bind-err false
         :quit-prompt (fn [])
         :print (fn [_result & _rest]
                  (on-setup ana/*cljs-ns* env/*compiler*))})))))

;; ---------------------------------------------------------------------------
;; nREPL handlers
;; ---------------------------------------------------------------------------

(defn repl-caught [session transport nrepl-msg err repl-env repl-options]
  (let [root-ex (#'clojure.main/root-cause err)]
    (when-not (instance? ThreadDeath root-ex)
      (set! *e err)
      (swap! session assoc #'*e err)
      (transport/send transport (response-for nrepl-msg {:status :eval-error
                                                         :ex (-> err class str)
                                                         :root-ex (-> root-ex class str)}))
      ((:caught repl-options default-caught) err repl-env repl-options))))

(defn ensure-close-teardown!
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
                           (when-let [repl-env (@session #'pb/*cljs-repl-env*)]
                             (tear-down! (get-repl-env repl-env)))
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
  (when-not (thread-bound? #'pb/*cljs-repl-env*)
    (throw (ex-info
            (str "cider.piggieback/cljs-repl must be invoked from within an nREPL "
                 "session, e.g. by evaluating it at a REPL connected to an nREPL "
                 "server that has the wrap-cljs-repl middleware. It cannot be "
                 "started from a plain Clojure REPL or from Leiningen's "
                 ":repl-options :init, both of which run outside of any session.")
            {:repl-env repl-env})))
  (try
    (let [repl-opts (repl-options repl-env)
          repl-env (delegating-repl-env repl-env)
          ;; have to initialise repl-options the same way they
          ;; are initilized inside of the cljs.repl/repl loop
          ;; because we are calling evaluate outside of the repl
          ;; loop.
          opts (build-opts repl-opts options)
          ;; Create the compiler env up front and hand it to the repl loop so we
          ;; always hold a reference to it, even if the setup eval errors and the
          ;; loop's :print callback (which would otherwise capture it) never runs
          ;; (issue #62).
          compiler-env (or (:compiler-env options) (default-compiler-env opts))
          {:keys [session ns]} ieval/*msg*
          init-ns (if ns (symbol ns) (get @session ns-var))]
      (set-current-ns! 'cljs.user)
      (let [out-target (atom *out*)
            err-target (atom *err*)]
        ;; Set up the repl env with forwarding writers in place so its
        ;; output-pump thread forwards to the current message's output rather
        ;; than to the one that started the REPL (issue #111).
        (binding [*out* (pb/forwarding-writer out-target)
                  *err* (pb/forwarding-writer err-target)]
          (setup-repl
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
               (swap! session assoc ns-var result-ns))
             (set! pb/*cljs-compiler-env* result-compiler-env))))
        (set! pb/*cljs-out-target* out-target)
        (set! pb/*cljs-err-target* err-target))
      ;; Record the compiler env unconditionally, in case the setup eval errored
      ;; and the on-setup callback never set it (issue #62).
      (set! pb/*cljs-compiler-env* compiler-env)
      (set! pb/*cljs-repl-env* repl-env)
      (set! pb/*cljs-repl-options* opts)
      ;; interruptible-eval is in charge of emitting the final :ns response in this context
      (set! pb/*original-clj-ns* *ns*)
      (set! pb/*cljs-warnings* (warnings))
      (set! pb/*cljs-warning-handlers* (warning-handlers))
      (set! *ns* (find-ns (current-ns)))
      ;; make sure a leaked JS runtime is torn down if the session is closed
      ;; without a :cljs/quit first
      (ensure-close-teardown! session)
      (println "To quit, type:" :cljs/quit))
    (catch Exception e
      (set! pb/*cljs-repl-env* nil)
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

(defn eval-cljs [repl-env env form file opts]
  (eval-form repl-env env form file opts (::print opts)))

(defn do-eval [{:keys [session transport ^String code file ns] :as msg}]
  (with-bindings (merge (eval-bindings (get @session #'pb/*cljs-compiler-env*)
                                       (get @session #'pb/*cljs-repl-env*))
                        ;; On nREPL 1.3+ the session middleware already binds the
                        ;; session contents, so we must not rebind them here.
                        (when-not compat/nrepl-1-3+?
                          @session)
                        (when ns
                          {ns-var (symbol ns)})
                        (compat/output-bindings msg))
    ;; Repoint the repl env's output-pump thread at this message's output (#111).
    (when pb/*cljs-out-target* (reset! pb/*cljs-out-target* *out*))
    (when pb/*cljs-err-target* (reset! pb/*cljs-err-target* *err*))
    (let [repl-env pb/*cljs-repl-env*
          repl-options pb/*cljs-repl-options*
          init-ns (current-ns)
          specials (special-fns repl-options)
          is-special-fn? (set (keys specials))]
      (try
        (let [form (read-form code)
              env  (analyzer-env init-ns)
              result (when form
                       (if (and (seq? form) (is-special-fn? (first form)))
                         (do ((get specials (first form)) repl-env env form repl-options)
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
                         (not= init-ns (current-ns)))
                     (current-ns))
            (swap! session assoc ns-var (current-ns)))
          (transport/send
           transport
           (response-for msg
                         (try
                           {:value (when (some? result)
                                     (edn-reader/read-string
                                      {:default pb/->UnknownTaggedLiteral}
                                      result))
                            :nrepl.middleware.print/keys #{:value}
                            :ns (current-ns)}
                           (catch Exception _
                             {:value (or result "nil")
                              :ns (current-ns)})))))
        (catch Throwable t
          (repl-caught session transport msg t repl-env repl-options))))))

;; only executed within the context of an nREPL session having *cljs-repl-env*
;; bound. Thus, we're not going through interruptible-eval, and the user's
;; Clojure session (dynamic environment) is not in place, so we need to go
;; through the `session` atom to access/update its vars. Same goes for load-file.
(defn- evaluate [{:keys [session transport ^String code] :as msg}]
  (if-not (-> code string/trim (string/ends-with? ":cljs/quit"))
    (do-eval msg)

    (let [actual-repl-env (get-repl-env (@session #'pb/*cljs-repl-env*))
          orig-ns (@session #'pb/*original-clj-ns*)]
      (tear-down! actual-repl-env)
      (swap! session assoc
             #'*ns* orig-ns
             #'pb/*cljs-repl-env* nil
             #'pb/*cljs-compiler-env* nil
             #'pb/*cljs-repl-options* nil
             ns-var 'cljs.user)
      (when (thread-bound? #'*ns*)
        (set! *ns* orig-ns))
      (transport/send transport (response-for msg
                                              :value "nil"
                                              :ns (str orig-ns))))))

(defn- do-load-file
  "Evaluate the ClojureScript source sent in the `load-file` message (its
  `:file`), against the active repl-env. Mirrors the binding setup of `do-eval`."
  [{:keys [session transport file file-path file-name] :as msg}]
  (with-bindings (merge (eval-bindings (get @session #'pb/*cljs-compiler-env*)
                                       (get @session #'pb/*cljs-repl-env*))
                        (when-not compat/nrepl-1-3+?
                          @session)
                        (compat/output-bindings msg))
    (when pb/*cljs-out-target* (reset! pb/*cljs-out-target* *out*))
    (when pb/*cljs-err-target* (reset! pb/*cljs-err-target* *err*))
    (let [repl-env pb/*cljs-repl-env*
          repl-options pb/*cljs-repl-options*]
      (try
        (load-source repl-env file (or file-path file-name "<cljs file>"))
        (.flush ^Writer *out*)
        (.flush ^Writer *err*)
        (transport/send transport (response-for msg
                                                :value "nil"
                                                :ns (str (current-ns))))
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

  Lets tooling detect whether the session is currently evaluating ClojureScript
  (and against which repl-env), instead of having to infer it out of band."
  [{:keys [session]}]
  (let [repl-env (when session (get @session #'pb/*cljs-repl-env*))]
    {:piggieback (cond-> {:cljs-repl (if repl-env "active" "inactive")}
                   repl-env (assoc :repl-env-type
                                   (.getName (class (get-repl-env repl-env)))))}))

(defn wrap-cljs-repl [handler]
  (fn [{:keys [session op] :as msg}]
    (let [handler (or (when-let [f (and (@session #'pb/*cljs-repl-env*)
                                        ({"eval" #'evaluate "load-file" #'load-file} op))]
                        (fn [msg]
                          (enqueue msg #(f msg))))
                      handler)]
      ;; ensure that bindings exist so cljs-repl can set!
      (when-not (@session #'pb/*cljs-repl-env*)
        (swap! session (partial merge {#'pb/*cljs-repl-env* pb/*cljs-repl-env*
                                       #'pb/*cljs-compiler-env* pb/*cljs-compiler-env*
                                       #'pb/*cljs-repl-options* pb/*cljs-repl-options*
                                       #'pb/*cljs-warnings* pb/*cljs-warnings*
                                       #'pb/*cljs-warning-handlers* pb/*cljs-warning-handlers*
                                       #'pb/*cljs-out-target* pb/*cljs-out-target*
                                       #'pb/*cljs-err-target* pb/*cljs-err-target*
                                       #'pb/*original-clj-ns* *ns*
                                       ns-var (current-ns)})))
      (handler msg))))
