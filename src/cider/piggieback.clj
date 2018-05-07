(ns cider.piggieback
  "nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling."
  {:author "Chas Emerick"}
  (:require [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [transport :as transport]
                                 [misc :refer (response-for returning)]
                                 [middleware :refer (set-descriptor!)])
            [clojure.tools.nrepl.middleware.interruptible-eval :as ieval]
            [clojure.java.io :as io]
            cljs.repl
            [cljs.env :as env]
            [cljs.analyzer :as ana]
            [cljs.tagged-literals :as tags]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as readers])
  (:import java.io.StringReader
           java.io.Writer)
  (:refer-clojure :exclude (load-file)))

;; this is the var that is checked by the middleware to determine whether an
;; active CLJS REPL is in flight
(def ^:private ^:dynamic *cljs-repl-env* nil)
(def ^:private ^:dynamic *cljs-compiler-env* nil)
(def ^:private ^:dynamic *cljs-repl-options* nil)
(def ^:private ^:dynamic *cljs-warning-handlers* nil)
(def ^:private ^:dynamic *original-clj-ns* nil)

(defn repl-caught [session transport nrepl-msg err repl-env repl-options]
  (let [root-ex (#'clojure.main/root-cause err)]
    (when-not (instance? ThreadDeath root-ex)
      (swap! session assoc #'*e err)
      (transport/send transport (response-for nrepl-msg {:status :eval-error
                                                         :ex (-> err class str)
                                                         :root-ex (-> root-ex class str)}))
      ((:caught repl-options cljs.repl/repl-caught) err repl-env repl-options))))

(defn- run-cljs-repl [{:keys [session transport ns] :as nrepl-msg}
                      code repl-env compiler-env options]
  (let [initns (if ns (symbol ns) (@session #'ana/*cljs-ns*))
        repl cljs.repl/repl*]
    (binding [ana/*cljs-ns* initns
              *out* (@session #'*out*)
              *err* (@session #'*err*)]
      (with-in-str (str code " :cljs/quit")
        (try
          (repl repl-env
                {;; doing this to avoid call to tear-down HACK!!!
                 :need-prompt (fn [] (throw
                                      (ex-info "Shortcutting REPL teardown"
                                               {::shortcut true})))
                 :init (fn [])
                 :prompt (fn [])
                 :bind-err false
                 :quit-prompt (fn [])
                 :compiler-env compiler-env
                 :print (fn [result & rest]
                          (when (or (not ns)
                                    (not= initns ana/*cljs-ns*))
                            (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
                          (set! *cljs-compiler-env* env/*compiler*))})
          (catch clojure.lang.ExceptionInfo e
            (when-not (-> e ex-data ::shortcut)
              (throw e))))))))

;; This function always executes when the nREPL session is evaluating Clojure,
;; via interruptible-eval, etc. This means our dynamic environment is in place,
;; so set! and simple dereferencing is available. Contrast w/ evaluate and
;; load-file below.
(defn cljs-repl
  "Starts a ClojureScript REPL over top an nREPL session.  Accepts
   all options usually accepted by e.g. cljs.repl/repl."
  [repl-env & {:as options}]
  ;; TODO I think we need a var to set! the compiler environment from the REPL
  ;; environment after each eval
  (try
    (let [repl-opts (cljs.repl/-repl-options repl-env)
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
      (run-cljs-repl (assoc ieval/*msg* ::first-cljs-repl true)
                     ;; TODO this needs to be looked at
                     (nrepl/code (ns cljs.user
                                   (:require [cljs.repl :refer-macros (source doc find-doc
                                                                              apropos dir pst)])))
                     repl-env nil options)
      ;; (clojure.pprint/pprint (:options @*cljs-compiler-env*))
      (set! *cljs-repl-env* repl-env)
      (set! *cljs-repl-options* opts)
      ;; interruptible-eval is in charge of emitting the final :ns response in this context
      (set! *original-clj-ns* *ns*)
      (set! *cljs-warning-handlers* cljs.analyzer/*cljs-warning-handlers*)
      (set! *ns* (find-ns ana/*cljs-ns*))
      (println "To quit, type:" :cljs/quit))
    (catch Exception e
      (set! *cljs-repl-env* nil)
      (throw e))))

;; mostly a copy/paste from interruptible-eval
(defn- enqueue [{:keys [session transport] :as msg} func]
  (ieval/queue-eval session @ieval/default-executor
                    (fn []
                      (alter-meta! session assoc
                                   :thread (Thread/currentThread)
                                   :eval-msg msg)
                      (binding [ieval/*msg* msg]
                        (func)
                        (transport/send transport (response-for msg :status :done))
                        (alter-meta! session dissoc :thread :eval-msg)))))

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

(defn eval-cljs [repl-env env form opts]
  (cljs.repl/evaluate-form repl-env
                           env
                           "<cljs repl>"
                           form
                           ((:wrap opts #'cljs.repl/wrap-fn) form)
                           opts))

(defn do-eval [{:keys [session transport ^String code ns] :as msg}]
  (binding [*out* (@session #'*out*)
            *err* (@session #'*err*)
            ana/*cljs-ns* (if ns (symbol ns) (@session #'ana/*cljs-ns*))
            env/*compiler* (@session #'*cljs-compiler-env*)
            cljs.analyzer/*cljs-warning-handlers*
            (@session #'*cljs-warning-handlers* cljs.analyzer/*cljs-warning-handlers*)]
    (let [repl-env (@session #'*cljs-repl-env*)
          repl-options (@session #'*cljs-repl-options*)
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
                         (eval-cljs repl-env env form repl-options)))]
          (.flush ^Writer *out*)
          (.flush ^Writer *err*)
          (when (and
                 (or (not ns)
                     (not= init-ns ana/*cljs-ns*))
                 ana/*cljs-ns*)
            (swap! session assoc #'ana/*cljs-ns* ana/*cljs-ns*))
          (transport/send
           transport
           (response-for msg
                         {:value (or result "nil")
                          :printed-value 1
                          :ns (@session #'ana/*cljs-ns*)})))
        (catch Throwable t
          (repl-caught session transport msg t repl-env repl-options))))))

;; only executed within the context of an nREPL session having *cljs-repl-env*
;; bound. Thus, we're not going through interruptible-eval, and the user's
;; Clojure session (dynamic environment) is not in place, so we need to go
;; through the `session` atom to access/update its vars. Same goes for load-file.
(defn- evaluate [{:keys [session transport ^String code] :as msg}]
  (if-not (.. code trim (endsWith ":cljs/quit"))
    (do-eval msg)
    (let [actual-repl-env (@session #'*cljs-repl-env*)]
      (cljs.repl/-tear-down actual-repl-env)
      (swap! session assoc
             #'*ns* (@session #'*original-clj-ns*)
             #'*cljs-repl-env* nil
             #'*cljs-compiler-env* nil
             #'*cljs-repl-options* nil
             #'ana/*cljs-ns* 'cljs.user)
      (transport/send transport (response-for msg
                                              :value "nil"
                                              :printed-value 1
                                              :ns (str (@session #'*original-clj-ns*)))))))

;; struggled for too long trying to interface directly with cljs.repl/load-file,
;; so just mocking a "regular" load-file call
;; this seems to work perfectly, *but* it only loads the content of the file from
;; disk, not the content of the file sent in the message (in contrast to nREPL on
;; Clojure). This is necessitated by the expectation of cljs.repl/load-file that
;; the file being loaded is on disk, in the location implied by the namespace
;; declaration.
;; TODO either pull in our own `load-file` that doesn't imply this, or raise the issue upstream.
(defn- load-file [{:keys [session transport file-path] :as msg}]
  (evaluate (assoc msg :code (format "(load-file %s)" (pr-str file-path)))))

(defn wrap-cljs-repl [handler]
  (fn [{:keys [session op] :as msg}]
    (let [handler (or (when-let [f (and (@session #'*cljs-repl-env*)
                                        ({"eval" #'evaluate "load-file" #'load-file} op))]
                        (fn [msg] (enqueue msg #(f msg))))
                      handler)]
      ;; ensure that bindings exist so cljs-repl can set!
      (when-not (contains? @session #'*cljs-repl-env*)
        (swap! session (partial merge {#'*cljs-repl-env* *cljs-repl-env*
                                       #'*cljs-compiler-env* *cljs-compiler-env*
                                       #'*cljs-repl-options* *cljs-repl-options*
                                       #'*cljs-warning-handlers* *cljs-warning-handlers*
                                       #'*original-clj-ns* *original-clj-ns*
                                       #'ana/*cljs-ns* ana/*cljs-ns*})))
      (handler msg))))

(set-descriptor! #'wrap-cljs-repl
                 {:requires #{"clone"}
                  ;; piggieback unconditionally hijacks eval and load-file
                  :expects #{"eval" "load-file"}
                  :handles {}})