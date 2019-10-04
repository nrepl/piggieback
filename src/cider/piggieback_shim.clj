(in-ns 'cider.piggieback)

(def ^:private
  fail-to-call
  (fn [& args]
    (throw (ex-info "Unable to load ClojureScript, did you forget a dependency?"
                    {}))))

(def cljs-repl fail-to-call)
(def repl-caught fail-to-call)
(def read-cljs-string fail-to-call)
(def eval-cljs fail-to-call)
(def do-eval fail-to-call)

(defn wrap-cljs-repl [handler]
  (fn [msg]
    (handler msg)))
