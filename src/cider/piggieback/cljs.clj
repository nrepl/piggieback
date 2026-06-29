(ns cider.piggieback.cljs
  "The ClojureScript evaluation core.

  All of Piggieback's coupling to the ClojureScript compiler internals
  (`cljs.repl`, `cljs.analyzer`, `cljs.env`, `cljs.closure`,
  `cljs.tagged-literals`) lives here, behind a small Clojure-facing API. The
  nREPL middleware (`cider.piggieback`) talks to this namespace and never
  reaches into the compiler directly, so the fragile, version-sensitive surface
  is confined to one place."
  (:require
   [clojure.string :as string]
   [clojure.tools.reader :as reader]
   [clojure.tools.reader.reader-types :as readers]
   [cljs.closure]
   [cljs.repl]
   [cljs.env :as env]
   [cljs.analyzer :as ana]
   [cljs.tagged-literals :as tags])
  (:import
   (java.io StringReader)))

;; ---------------------------------------------------------------------------
;; Analyzer / compiler state
;; ---------------------------------------------------------------------------

(def ns-var
  "The ClojureScript analyzer's current-namespace var. Exposed so the middleware
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
