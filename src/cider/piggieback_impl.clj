(in-ns 'cider.piggieback)

(require
 '[clojure.java.io :as io]
 '[clojure.main]
 '[clojure.string :as string]
 '[clojure.tools.reader :as reader]
 '[clojure.tools.reader.edn :as edn-reader]
 '[clojure.tools.reader.reader-types :as readers]
 '[cljs.closure]
 '[cljs.repl]
 '[cljs.env :as env]
 '[cljs.analyzer :as ana]
 '[cljs.tagged-literals :as tags]
 '[nrepl.core :as nrepl]
 '[nrepl.middleware :as middleware]
 '[nrepl.middleware.interruptible-eval :as ieval]
 '[nrepl.misc :as misc :refer [response-for]]
 '[nrepl.transport :as transport])

(import
 '(java.io StringReader Writer))

;; this is the var that is checked by the middleware to determine whether an
;; active CLJS REPL is in flight
(def ^:private ^:dynamic *cljs-repl-env* nil)
(def ^:private ^:dynamic *cljs-compiler-env* nil)
(def ^:private ^:dynamic *cljs-repl-options* nil)
(def ^:private ^:dynamic *cljs-warnings* nil)
(def ^:private ^:dynamic *cljs-warning-handlers* nil)
(def ^:private ^:dynamic *original-clj-ns* nil)

;; ---------------------------------------------------------------------------
;; Delegating Repl Env
;; ---------------------------------------------------------------------------

;; We have to create a delegating ReplEnv to prevent the call to -tear-down
;; this could be avoided if we could override -tear-down only

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

(deftype ^:private UnknownTaggedLiteral [tag data])

(defmethod print-method UnknownTaggedLiteral
  [^UnknownTaggedLiteral this ^java.io.Writer w]
  (.write w (str "#" (.tag this) (.data this))))

(defn- generate-delegating-repl-env [repl-env]
  (let [repl-env-class (class repl-env)
        classname (string/replace (.getName repl-env-class) \. \_)
        dclassname (str "Delegating" classname)]
    (eval
     (list*
      'deftype (symbol dclassname)
      '([repl-env]
        cider.piggieback/GetReplEnv
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
        (assoc [_ k v] (#'cider.piggieback/delegating-repl-env (assoc repl-env k v)))
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

(defn- delegating-repl-env [repl-env]
  (let [ctor (generate-delegating-repl-env repl-env)]
    (ctor repl-env)))

;; ---------------------------------------------------------------------------

(defn repl-caught [session transport nrepl-msg err repl-env repl-options]
  (let [root-ex (#'clojure.main/root-cause err)]
    (when-not (instance? ThreadDeath root-ex)
      (set! *e err)
      (swap! session assoc #'*e err)
      (transport/send transport (response-for nrepl-msg {:status :eval-error
                                                         :ex (-> err class str)
                                                         :root-ex (-> root-ex class str)}))
      ((:caught repl-options cljs.repl/repl-caught) err repl-env repl-options))))

(defn- run-cljs-repl [{:keys [session transport ns] :as nrepl-msg}
                      code repl-env compiler-env options]
  (let [initns (if ns (symbol ns) (@session #'ana/*cljs-ns*))
        repl cljs.repl/repl*]
    (binding [ana/*cljs-ns* initns]
      (with-in-str (str code " :cljs/quit")
        (repl repl-env
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
                :print (fn [result & rest]
                         (when (or (not ns)
                                   (not= initns ana/*cljs-ns*))
                           (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
                         (set! *cljs-compiler-env* env/*compiler*))}))))))

;; This function always executes when the nREPL session is evaluating Clojure,
;; via interruptible-eval, etc. This means our dynamic environment is in place,
;; so set! and simple dereferencing is available. Contrast w/ evaluate and
;; load-file below.
(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session.  Accepts
   all options usually accepted by e.g. cljs.repl/repl."
  [repl-env & {:as options}]
  (try
    (let [repl-opts (cljs.repl/-repl-options repl-env)
          repl-env (delegating-repl-env repl-env)
          ;; have to initialise repl-options the same way they
          ;; are initilized inside of the cljs.repl/repl loop
          ;; because we are calling evaluate outside of the repl
          ;; loop.
          opts (merge
                {:def-emits-var true}
                (cljs.closure/add-implicit-options
                 (merge-with (fn [a b] (if (nil? b) a b))
                             repl-opts options)))]
      (set! ana/*cljs-ns* 'cljs.user)
      ;; this will implicitly set! *cljs-compiler-env*
      (run-cljs-repl ieval/*msg*
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
                     repl-env nil options)
      ;; (clojure.pprint/pprint (:options @*cljs-compiler-env*))
      (set! *cljs-repl-env* repl-env)
      (set! *cljs-repl-options* opts)
      ;; interruptible-eval is in charge of emitting the final :ns response in this context
      (set! *original-clj-ns* *ns*)
      (set! *cljs-warnings* ana/*cljs-warnings*)
      (set! *cljs-warning-handlers* ana/*cljs-warning-handlers*)
      (set! *ns* (find-ns ana/*cljs-ns*))
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
                    (java.io.StringReader. form-str))))))

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

(defn eval-cljs [repl-env env form file opts]
  (cljs.repl/evaluate-form repl-env
                           env
                           (or file "<cljs repl>")
                           form
                           ((:wrap opts
                                   (if (contains? #{"nrepl.util.print/pr" "cider.nrepl.pprint/pr"} (::print opts))
                                     #'cljs.repl/wrap-fn
                                     #'pprint-repl-wrap-fn)) form)
                           opts))

(defn- output-bindings [{:keys [session] :as msg}]
  (when-let [replying-PrintWriter (resolve 'nrepl.middleware.print/replying-PrintWriter)]
    {#'*out* (replying-PrintWriter :out msg {})
     #'*err* (replying-PrintWriter :err msg {})}))

(def nrepl-1-3+? (some? (resolve 'ieval/evaluator)))

(defn do-eval [{:keys [session transport ^String code file ns] :as msg}]
  (with-bindings (merge {#'ana/*cljs-warnings* ana/*cljs-warnings*
                         #'ana/*cljs-warning-handlers* ana/*cljs-warning-handlers*
                         #'ana/*unchecked-if* ana/*unchecked-if*
                         #'env/*compiler* (get @session #'*cljs-compiler-env*)
                         #'cljs.repl/*repl-env* (get @session #'*cljs-repl-env*)}
                        ;; ieval/evaluator appeared in nREPL 1.3 where session
                        ;; contents are already bound by session middleware and
                        ;; should NOT be rebound here.
                        (when-not nrepl-1-3+?
                          @session)
                        (when ns
                          {#'ana/*cljs-ns* (symbol ns)})
                        (output-bindings msg))
    (let [repl-env *cljs-repl-env*
          repl-options *cljs-repl-options*
          init-ns ana/*cljs-ns*
          special-fns (merge cljs.repl/default-special-fns (:special-fns repl-options))
          is-special-fn? (set (keys special-fns))]
      (try
        (let [form (read-cljs-string code)
              env  (assoc (ana/empty-env) :ns (ana/get-namespace init-ns))
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
                         (not= init-ns ana/*cljs-ns*))
                     ana/*cljs-ns*)
            (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
          (transport/send
           transport
           (response-for msg
                         (try
                           {:value (when (some? result)
                                     (edn-reader/read-string
                                      {:default ->UnknownTaggedLiteral}
                                      result))
                            :nrepl.middleware.print/keys #{:value}
                            :ns (get @session #'ana/*cljs-ns*)}
                           (catch Exception _
                             {:value (or result "nil")
                              :ns (get @session #'ana/*cljs-ns*)})))))
        (catch Throwable t
          (repl-caught session transport msg t repl-env repl-options))))))

;; only executed within the context of an nREPL session having *cljs-repl-env*
;; bound. Thus, we're not going through interruptible-eval, and the user's
;; Clojure session (dynamic environment) is not in place, so we need to go
;; through the `session` atom to access/update its vars. Same goes for load-file.
(defn- evaluate [{:keys [session transport ^String code] :as msg}]
  (if-not (-> code string/trim (string/ends-with? ":cljs/quit"))
    (do-eval msg)

    (let [actual-repl-env (get-repl-env (@session #'*cljs-repl-env*))
          orig-ns (@session #'*original-clj-ns*)]
      (cljs.repl/-tear-down actual-repl-env)
      (swap! session assoc
             #'*ns* orig-ns
             #'*cljs-repl-env* nil
             #'*cljs-compiler-env* nil
             #'*cljs-repl-options* nil
             #'ana/*cljs-ns* 'cljs.user)
      (when (thread-bound? #'*ns*)
        (set! *ns* orig-ns))
      (transport/send transport (response-for msg
                                              :value "nil"
                                              :ns (str orig-ns))))))

;; struggled for too long trying to interface directly with cljs.repl/load-file,
;; so just mocking a "regular" load-file call
;; this seems to work perfectly, *but* it only loads the content of the file from
;; disk, not the content of the file sent in the message (in contrast to nREPL on
;; Clojure). This is necessitated by the expectation of cljs.repl/load-file that
;; the file being loaded is on disk, in the location implied by the namespace
;; declaration.
;; TODO: Either pull in our own `load-file` that doesn't imply this, or raise the issue upstream.
(defn- load-file [{:keys [session transport file-path] :as msg}]
  (evaluate (assoc msg :code (format "(load-file %s)" (pr-str file-path)))))

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
                                       #'*original-clj-ns* *ns*
                                       #'ana/*cljs-ns* ana/*cljs-ns*})))
      (handler msg))))
