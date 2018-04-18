(ns user
  (:require
   [cider.piggieback :as pback]
   [cljs.repl :as repl]
   [cljs.repl.nashorn :as nash]))

(defn cljs []
  (pback/cljs-repl (nash/repl-env :verbose true :repl-verbose true)))

(defn cljs* []
  (repl/repl (nash/repl-env :verbose true :repl-verbose true)))
