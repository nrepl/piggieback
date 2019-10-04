(ns cider.piggieback
  "nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [load-file])
  (:require
   [nrepl.middleware :refer [set-descriptor!]]))

(defmacro ^:private if-ns
  [ns body else]
  (if (try
        (require ns)
        true
        (catch java.io.FileNotFoundException e
          false))
    `~body
    `~else))

(if-ns cljs.repl
       (load "piggieback_impl")
       (load "piggieback_shim"))

(set-descriptor! #'wrap-cljs-repl
                 {:requires #{"clone"}
                  ;; piggieback unconditionally hijacks eval and load-file
                  :expects #{"eval" "load-file"}
                  :handles {}})
