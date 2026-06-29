(ns cider.piggieback.compat
  "Shims for the differences between the supported nREPL versions (1.0+).

  Piggieback supports a range of nREPL releases whose internals differ in a few
  places. Those differences are detected and papered over here, via runtime
  `resolve` checks, so the rest of the codebase can stay version-agnostic."
  ;; interruptible-eval must be loaded before the load-time `nrepl-1-3+?`
  ;; resolve below runs, otherwise the var lookup yields nil and we'd misdetect
  ;; 1.3+ as older.
  (:require
   [nrepl.middleware.interruptible-eval]))

(def nrepl-1-3+?
  "True on nREPL 1.3 or newer.

  As of nREPL 1.3 the session middleware already establishes per-message thread
  bindings for the session's dynamic vars, so Piggieback must NOT rebind them
  from the session atom itself (doing so would clobber the values the session
  middleware set up). On older versions Piggieback has to bind them. Detected
  via the `evaluator` var that nREPL 1.3 introduced."
  (some? (resolve 'nrepl.middleware.interruptible-eval/evaluator)))

(defn output-bindings
  "Return a binding map routing `*out*`/`*err*` through nREPL's print middleware
  for `msg`, or nil when the print middleware isn't available.

  Uses `replying-PrintWriter`, which streams output back to the client as it is
  produced rather than buffering it until the evaluation completes."
  [msg]
  (when-let [replying-PrintWriter (resolve 'nrepl.middleware.print/replying-PrintWriter)]
    {#'*out* (replying-PrintWriter :out msg {})
     #'*err* (replying-PrintWriter :err msg {})}))
