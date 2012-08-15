(ns ^{:doc "nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling."
      :author "Chas Emerick"}
     cemerick.piggieback
  (:require [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [transport :as transport]
                                 [server :as server]
                                 [misc :refer (returning)])
            [clojure.tools.nrepl.middleware.load-file :as load-file]
            [cljs.repl :as cljsrepl]
            [cljs.analyzer :as ana]
            [cljs.tagged-literals :as tags]
            [cljs.repl.rhino :as rhino])
  (:import (org.mozilla.javascript Context ScriptableObject)
           clojure.lang.LineNumberingPushbackReader
           java.io.StringReader))

(def ^:private ^:dynamic *cljs-repl-env* nil)
(def ^:private ^:dynamic *eval* nil)
(def ^:private ^:dynamic *cljs-repl-options* nil)

(defmacro ^:private squelch-rhino-context-error
  "Catches and silences the exception thrown by (Context/exit)
   when it is called without a corresponding (Context/enter).
   Needed because rhino/repl-env calls Context/enter without
   a corresponding Context/exit; it assumes:

   (a) the context will only ever be used on one thread
   (b) cljsrepl/repl will clean up the context when the
       command-line cljs repl exits"
  [& body]
  `(try
     ~@body
     (catch IllegalStateException e#
       (when-not (-> e# .getMessage (.contains "Context.exit without previous Context.enter"))
         (throw e#)))))

(defmacro ^:private with-rhino-context
  [& body]
  `(try
    (Context/enter)
    ~@body
    (finally
      ; -tear-down for rhino environments always calls Context/exit, so we need
      ; to kill the resulting error to avoid an exception printing on :cljs/quit
      (squelch-rhino-context-error (Context/exit)))))

(defn- map-stdout
  [rhino-env out]
  (ScriptableObject/putProperty
    (:scope rhino-env)
    "out"
    (Context/javaToJS out (:scope rhino-env))))

(defn rhino-repl-env
  "Returns a new Rhino ClojureScript REPL environment that has been
   set up via `cljs.repl/-setup`."
  []
  (with-rhino-context
    (doto (rhino/repl-env)
      cljsrepl/-setup
      ; rhino/rhino-setup maps System/out to "out" and therefore the target of
      ; cljs' *print-fn*! :-(
      (map-stdout *out*)
      ; rhino/repl-env calls (Context/enter) without a (Context/exit)
      (squelch-rhino-context-error
        (Context/exit)))))

(defn- quit-cljs-repl
  []
  (squelch-rhino-context-error
    (cljsrepl/-tear-down *cljs-repl-env*))
  (set! *cljs-repl-env* nil)
  (set! *eval* nil)
  (set! ana/*cljs-ns* 'cljs.user))

(defn cljs-eval
  "Evaluates the expression [expr] (should already be read) using the
   given ClojureScript REPL environment [repl-env] and a map of
   ClojureScript REPL options: :verbose, :warn-on-undeclared, and
   :special-fns, each with the same acceptable values and semantics as
   the \"regular\" ClojureScript REPL.

   This is generally not going to be used by end users; rather, 
   as the basis of alternative (usually not-Rhino, e.g. node/V8)
   `eval` functions passed to `cljs-repl`."
  [repl-env expr {:keys [verbose warn-on-undeclared special-fns]}]
  (binding [cljsrepl/*cljs-verbose* verbose
            ana/*cljs-warn-on-undeclared* warn-on-undeclared]
    (let [special-fns (merge cljsrepl/default-special-fns special-fns)
          is-special-fn? (set (keys special-fns))]
      (cond
        (= expr :cljs/quit) (do (quit-cljs-repl) :cljs/quit)
        
        (and (seq? expr) (is-special-fn? (first expr)))
        (apply (get special-fns (first expr)) repl-env (rest expr))
        
        :default
        (let [ret (cljsrepl/evaluate-form repl-env
                    {:context :statement :locals {}
                     :ns (ana/get-namespace ana/*cljs-ns*)}
                    "<cljs repl>"
                    expr
                    (#'cljsrepl/wrap-fn expr))]
          (try
            (read-string ret)
            (catch Exception _
              (when (string? ret)
                (println ret)))))))))

(defn- wrap-exprs
  [& exprs]
  (for [expr exprs]
    `(#'*eval* @#'*cljs-repl-env*
               '~expr
               @#'*cljs-repl-options*)))

(defn- load-file-contents
  [repl-env code file-path file-name]
  (cljs.repl/load-stream repl-env file-name (java.io.StringReader. code)))

(defn- load-file-code
  [code file-path file-name]
  (wrap-exprs (list `load-file-contents code file-path file-name)))

(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session.  Accepts
   all options usually accepted by e.g. cljs.repl/repl. Also accepts optional
   configuration via kwargs:

     :repl-env - a ClojureScript REPL environment (defaults to a new Rhino
                 environment [from `rhino-repl-env`])
     :eval - a function of three arguments
             ([repl-env expression cljs-repl-options], corresponding to `cljs-eval`)
             that is called once for each ClojureScript expression to be evaluated."
  [& {:keys [repl-env eval] :as options}]
  (let [repl-env (or repl-env (rhino-repl-env))
        eval (or eval #(with-rhino-context (apply cljs-eval %&)))]
    ; :warn-on-undeclared default from ClojureScript's script/repljs
    (set! *cljs-repl-options* (-> (merge {:warn-on-undeclared true} options)
                                (update-in [:special-fns] assoc
                                           `load-file-contents #'load-file-contents)
                                (dissoc :repl-env :eval)))
    (set! *cljs-repl-env* repl-env)
    (set! *eval* eval)
    (set! ana/*cljs-ns* 'cljs.user)
    
    (print "Type `")
    (pr :cljs/quit)
    (println "` to stop the ClojureScript REPL")))

(defn- prep-code
  [{:keys [code session] :as msg}]
  (let [code (if-not (string? code)
               code
               (let [reader (LineNumberingPushbackReader. (StringReader. code))
                     end (Object.)]
                 (->> #(binding [*ns* (create-ns ana/*cljs-ns*)
                                 *data-readers* tags/*cljs-data-readers*]
                         (try
                           (read reader false end)
                           (catch Exception e
                             (binding [*out* (@session #'*err*)]
                               (println (.getMessage e))
                               ::error))))
                   repeatedly
                   (take-while (complement #{end}))
                   (remove #{::error}))))]
    (assoc msg :code (apply wrap-exprs code))))

(defn- cljs-ns-transport
  [transport]
  (reify clojure.tools.nrepl.transport.Transport
    (recv [this] (transport/recv transport))
    (recv [this timeout] (transport/recv transport timeout))
    (send [this resp]
      (let [resp (if (and *cljs-repl-env* (:ns resp))
                   (assoc resp :ns (str ana/*cljs-ns*))
                   resp)]
        (transport/send transport resp)))))

(defn wrap-cljs-repl
  [h]
  (fn [{:keys [op session transport] :as msg}]
    (let [cljs-active? (@session #'*cljs-repl-env*)
          msg (assoc msg :transport (cljs-ns-transport transport))
          msg (if (and cljs-active? (= op "eval")) (prep-code msg) msg)]
      ; ensure that bindings exist so cljs-repl can set! 'em
      (when-not (contains? @session #'*cljs-repl-env*)
        (swap! session (partial merge {#'*cljs-repl-env* *cljs-repl-env*
                                       #'*eval* *eval*
                                       #'*cljs-repl-options* *cljs-repl-options*
                                       #'ana/*cljs-ns* ana/*cljs-ns*})))
      
      (with-bindings (if cljs-active?
                       {#'load-file/load-file-code load-file-code}
                       {})
        (h msg)))))
