(ns user
  (:require
   [cider.piggieback :as pback]))

(defmacro ^:private when-ns
  [ns & body]
  (if (try
        (require ns)
        true
        (catch java.io.FileNotFoundException e
          false))
    `(do ~@body)
    `(do)))

(when-ns cljs.repl
  (require '[cljs.repl :as repl]
           '[cljs.repl.node :as node])


 (defn cljs-node []
   (pback/cljs-repl (node/repl-env)))

 (defn cljs* []
   (repl/repl (node/repl-env))))
