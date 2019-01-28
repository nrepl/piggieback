(ns user
  (:require
   ;; this unfortunately precludes testing against nrepl/nrepl
   ;; better to have this code as a script to include during development?
   [clojure.tools.nrepl]
   [cljs.repl :as repl]
   [cljs.repl.nashorn :as nash]
   [cljs.repl.node :as node]))

(require '[cider.piggieback :as pback])

(defn cljs []
  (pback/cljs-repl (nash/repl-env :verbose true :repl-verbose true)))

(defn cljs-node []
  (pback/cljs-repl (node/repl-env)))

(defn cljs* []
  (repl/repl (nash/repl-env :verbose true :repl-verbose true)))
