(in-ns 'cider.piggieback)

(def ^:private
  fail-to-call
  (fn [& _args]
    (throw (ex-info "Unable to load ClojureScript, did you forget a dependency?"
                    {}))))

(def cljs-repl fail-to-call)
(def repl-caught fail-to-call)
(def read-cljs-string fail-to-call)
(def eval-cljs fail-to-call)
(def do-eval fail-to-call)

(defn describe-cljs
  "A describe-fn reporting that ClojureScript support is unavailable: this shim
  is loaded precisely because ClojureScript is not on the classpath."
  [_msg]
  {:piggieback {:cljs-repl "unavailable"}})

(defn wrap-cljs-repl [handler]
  (fn [msg]
    (handler msg)))
