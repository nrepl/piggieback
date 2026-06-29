(ns cider.piggieback
  "nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling.

  This is the public face of Piggieback. It has no hard dependency on
  ClojureScript: when ClojureScript is on the classpath the real implementation
  in `cider.piggieback.cljs` is loaded lazily on first use; otherwise the
  middleware is a no-op and `cljs-repl` reports the missing dependency.

  The session-state dynamic vars live here (rather than in the implementation
  namespace) because they are part of Piggieback's contract: other middleware,
  notably cider-nrepl, read them out of the session by their fully-qualified
  name. Keeping them here also lets the implementation namespace depend on this
  one without a load cycle."
  {:author "Chas Emerick"}
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :as print]))

;; `*cljs-repl-env*` is checked by the middleware to determine whether an active
;; ClojureScript REPL is in flight; it (and `*cljs-compiler-env*`) are also read
;; out of the session by other middleware, so they must stay interned here under
;; these names.
(def ^:dynamic *cljs-repl-env* nil)
(def ^:dynamic *cljs-compiler-env* nil)
(def ^:dynamic *cljs-repl-options* nil)
(def ^:dynamic *cljs-warnings* nil)
(def ^:dynamic *cljs-warning-handlers* nil)
(def ^:dynamic *original-clj-ns* nil)

;; Atoms holding the Writer that the ClojureScript repl env's output should be
;; forwarded to. They are repointed at the current message's output on every
;; evaluation, see `forwarding-writer` and issue #111 for the details.
(def ^:dynamic *cljs-out-target* nil)
(def ^:dynamic *cljs-err-target* nil)

(defn forwarding-writer
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

(deftype UnknownTaggedLiteral [tag data])

(defmethod print-method UnknownTaggedLiteral
  [^UnknownTaggedLiteral this ^java.io.Writer w]
  ;; Recurse through print-method (rather than str) so the data round-trips
  ;; correctly: strings keep their quotes, nested values are printed readably,
  ;; and the tag and data are separated by a space (issue #120).
  (.write w "#")
  (print-method (.tag this) w)
  (.write w " ")
  (print-method (.data this) w))

(def ^:private cljs-available?
  "True when ClojureScript is on the classpath. Checked against `cljs.repl`
  directly (not the implementation namespace) so that resolving availability
  doesn't trigger a load cycle."
  (try
    (require 'cljs.repl)
    true
    (catch Throwable _ false)))

(defn- impl
  "Resolve `sym` in the ClojureScript implementation namespace, loading it on
  first use. Returns nil when ClojureScript isn't available."
  [sym]
  (when cljs-available?
    (requiring-resolve (symbol "cider.piggieback.cljs" (name sym)))))

;; The functions below are the public API. With ClojureScript present they
;; delegate to the implementation namespace; without it they degrade to a no-op
;; (or, for `cljs-repl`, a clear error).

(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session. Accepts all options
  usually accepted by e.g. cljs.repl/repl."
  [repl-env & options]
  (if-let [f (impl 'cljs-repl)]
    (apply f repl-env options)
    (throw (ex-info "Unable to load ClojureScript, did you forget a dependency?" {}))))

(defn read-cljs-string [form-str]
  (when-let [f (impl 'read-form)]
    (f form-str)))

(defn eval-cljs [repl-env env form file opts]
  ((impl 'eval-cljs) repl-env env form file opts))

(defn do-eval [msg]
  ((impl 'do-eval) msg))

(defn repl-caught [session transport nrepl-msg err repl-env repl-options]
  ((impl 'repl-caught) session transport nrepl-msg err repl-env repl-options))

(defn wrap-cljs-repl [handler]
  (if-let [f (impl 'wrap-cljs-repl)]
    (f handler)
    ;; ClojureScript isn't available, so do nothing.
    handler))

(defn- describe-cljs [msg]
  (if-let [f (impl 'describe-cljs)]
    (f msg)
    {:piggieback {:cljs-repl "unavailable"}}))

(set-descriptor! #'wrap-cljs-repl
                 {:requires #{"clone" #'print/wrap-print}
                  ;; piggieback unconditionally hijacks eval and load-file
                  :expects #{"eval" "load-file"}
                  :handles {}
                  ;; contributes the session's ClojureScript status to `describe`
                  :describe-fn describe-cljs})
