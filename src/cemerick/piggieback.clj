(ns ^{:doc "nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling."
      :author "Chas Emerick"}
     cemerick.piggieback
  (:require [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [transport :as transport]
                                 [misc :refer (response-for returning)]
                                 [middleware :refer (set-descriptor!)])
            [clojure.tools.nrepl.middleware.interruptible-eval :refer (*msg*)]
            cljs.repl
            [cljs.env :as env]
            [cljs.analyzer :as ana]
            [cljs.repl.rhino :as rhino])
  (:import (org.mozilla.javascript Context ScriptableObject)
           clojure.lang.LineNumberingPushbackReader
           java.io.StringReader
           java.io.Writer)
  (:refer-clojure :exclude (load-file)))

(set! *warn-on-reflection* true)

; this is the var that is checked by the middleware to determine whether an
; active CLJS REPL is in flight
(def ^:private ^:dynamic *cljs-repl-env* nil)
(def ^:private ^:dynamic *cljs-compiler-env* nil)
(def ^:private ^:dynamic *cljs-repl-options* nil)
(def ^:private ^:dynamic *original-clj-ns* nil)

; ================ Rhino junk =================

(defn- rhino-repl-env?
  [repl-env]
  (instance? cljs.repl.rhino.RhinoEnv repl-env))

(defmacro ^:private squelch-rhino-context-error
  "Catches and silences the exception thrown by (Context/exit)
   when it is called without a corresponding (Context/enter).
   Needed because rhino/repl-env calls Context/enter without
   a corresponding Context/exit; it assumes:

   (a) the context will only ever be used on one thread
   (b) cljs.repl/repl will clean up the context when the
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

(defn- setup-rhino-env
  [rhino-env options]
  (with-rhino-context
    (let [ret (cljs.repl/-setup rhino-env options)]
      ; rhino/rhino-setup maps System/out to "out" and therefore the target of
      ; cljs' *print-fn*! :-(
      (map-stdout rhino-env *out*)
      ; rhino/repl-env calls (Context/enter) without a (Context/exit)
      (squelch-rhino-context-error (Context/exit))
      ret)))

; ================ end Rhino junk =============

; this to avoid setting up the "real" REPL environment every time we enter
; cljs.repl/repl*, and to squelch -tear-down entiretly
(deftype DelegatingREPLEnv [repl-env ^:volatile-mutable setup-return-val]
  cljs.repl/IReplEnvOptions
  (-repl-options [_] (cljs.repl/-repl-options repl-env))
  cljs.repl/IJavaScriptEnv
  (-setup [this options] 
    (when (nil? setup-return-val)
      (set! setup-return-val (atom (if (rhino-repl-env? repl-env)
                                     (setup-rhino-env repl-env options)
                                     (cljs.repl/-setup repl-env options)))))
    @setup-return-val)
  (-evaluate [this a b c] (cljs.repl/-evaluate repl-env a b c))
  (-load [this ns url] (cljs.repl/-load repl-env ns url))
  (-tear-down [_])
  clojure.lang.ILookup
  (valAt [_ k] (get repl-env k))
  (valAt [_ k default] (get repl-env k default))
  clojure.lang.Seqable
  (seq [_] (seq repl-env))
  clojure.lang.Associative
  (containsKey [_ k] (contains? repl-env k))
  (entryAt [_ k] (find repl-env k))
  (assoc [_ k v] (DelegatingREPLEnv. (assoc repl-env k v) setup-return-val))
  clojure.lang.IPersistentCollection
  (count [_] (count repl-env))
  (cons [_ entry] (conj repl-env entry))
  ; pretty meaningless; most REPL envs are records for the assoc'ing, but they're not values
  (equiv [_ other] false))

(defn- run-cljs-repl [{:keys [session transport ns squelch-result] :as nrepl-msg}
                       code repl-env compiler-env options]
  (let [initns (if ns (symbol ns) (@session #'ana/*cljs-ns*))
        repl (if (rhino-repl-env? (.-repl-env ^DelegatingREPLEnv repl-env))
               #(with-rhino-context (apply cljs.repl/repl* %&))
               cljs.repl/repl*)
        flush (fn []
                (.flush ^Writer (@session #'*out*))
                (.flush ^Writer (@session #'*err*)))]
    ;; do we care about line numbers in the REPL?
    (binding [*in* (-> (str code " :cljs/quit") StringReader. LineNumberingPushbackReader.)
              *out* (@session #'*out*)
              *err* (@session #'*err*)
              ana/*cljs-ns* initns]
      (repl repl-env
        {:need-prompt (constantly false)
         :init (fn [])
         :prompt (fn [])
         :bind-err false
         :quit-prompt (fn [])
         :compiler-env compiler-env
         :flush flush
         :print (fn [result]
                  ; make sure that all *printed* output is flushed before sending results of evaluation
                  (flush)
                  (when (or (not ns)
                          (not= initns ana/*cljs-ns*))
                    (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
                  (when-not squelch-result
                    (transport/send transport (response-for nrepl-msg
                                                {:value result
                                                 :ns (@session #'ana/*cljs-ns*)}))))
         :caught (fn [err repl-env repl-options]
                   (let [root-ex (#'clojure.main/root-cause err)]
                     (when-not (instance? ThreadDeath root-ex)
                       (transport/send transport (response-for nrepl-msg {:status :eval-error
                                                                          :ex (-> err class str)
                                                                          :root-ex (-> root-ex class str)}))
                       (cljs.repl/repl-caught err repl-env repl-options))))}))))

(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session.  Accepts
   all options usually accepted by e.g. cljs.repl/repl."
  [repl-env & {:as options}]
  ; TODO I think we need a var to set! the compiler environment from the REPL
  ; environment after each eval
  (try
    (let [repl-env (DelegatingREPLEnv. repl-env nil)
          compiler-env (env/default-compiler-env (cljs.closure/add-implicit-options options))]
      (run-cljs-repl (assoc *msg* :squelch-result true)
        (nrepl/code (ns cljs.user
                      (:require [cljs.repl :refer-macros (source doc find-doc
                                                           apropos dir pst)])))
        repl-env compiler-env options)
      (set! *cljs-repl-env* repl-env)
      (set! *cljs-compiler-env* compiler-env)
      (set! *cljs-repl-options* options)
      (set! *original-clj-ns* *ns*)
      (set! *ns* (find-ns ana/*cljs-ns*))
      (println "To quit, type:" :cljs/quit))
    (catch Exception e
      (set! *cljs-repl-env* nil)
      (throw e))))

(defn- evaluate [{:keys [session transport ^String code] :as msg}]
  ; we append a :cljs/quit to every chunk of code evaluated so we can break out of cljs.repl/repl*'s loop,
  ; so we need to go a gnarly little stringy check here to catch any actual user-supplied exit
  (if-not (.. code trim (endsWith ":cljs/quit"))
    (apply run-cljs-repl msg code
      (map @session [#'*cljs-repl-env* #'*cljs-compiler-env* #'*cljs-repl-options*]))
    (do
      (cljs.repl/-tear-down (@session #'*cljs-repl-env*))
      (swap! session assoc
        #'*ns* (@session #'*original-clj-ns*)
        #'*cljs-repl-env* nil
        #'*cljs-compiler-env* nil
        #'*cljs-repl-options* nil
        #'ana/*cljs-ns* 'cljs.user)
      (transport/send transport (response-for msg
                                  :value "nil"
                                  :ns (str (@session #'*original-clj-ns*))))))

  (transport/send transport (response-for msg :status :done)))

(defn- load-file [{:keys [session transport file file-name] :as msg}]
  (cljs.env/with-compiler-env (@session #'*cljs-compiler-env*)
    (binding [ana/*cljs-ns* (@session #'ana/*cljs-ns*)]
      (cljs.repl/load-stream (@session #'*cljs-repl-env*) file-name (StringReader. file))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-cljs-repl [handler]
  (fn [{:keys [session op] :as msg}]
    (let [handler (or (and (@session #'*cljs-repl-env*)
                        ({"eval" #'evaluate "load-file" #'load-file} op))
                    handler)]
      ; ensure that bindings exist so cljs-repl can set!
      (when-not (contains? @session #'*cljs-repl-env*)
        (swap! session (partial merge {#'*cljs-repl-env* *cljs-repl-env*
                                       #'*cljs-compiler-env* *cljs-compiler-env*
                                       #'*cljs-repl-options* *cljs-repl-options*
                                       #'*original-clj-ns* *original-clj-ns*
                                       #'ana/*cljs-ns* ana/*cljs-ns*})))
      (handler msg))))

(set-descriptor! #'wrap-cljs-repl
  {:requires #{"clone"}
   :expects #{"load-file" "eval"}
   :handles {}})
