(ns cider.piggieback-data-readers-test
  "Regression test for https://github.com/nrepl/piggieback/issues/128: custom
  data readers declared in data_readers.cljc should be honored when reading
  ClojureScript forms at the REPL. Needs no JavaScript runtime."
  (:require
   [clojure.test :refer [deftest is]]
   [cljs.analyzer :as ana]
   [cljs.env :as env]))

(require 'cider.piggieback 'cider.test-data-readers)

(deftest custom-data-readers-are-honored
  ;; read-cljs-string needs a compiler env and a current CLJS ns, just like it
  ;; has during real evaluation.
  (env/with-compiler-env (env/default-compiler-env)
    (binding [ana/*cljs-ns* 'cljs.user]
      (is (= [:cider.test-data-readers/lstr "gaol@en-uk"]
             (cider.piggieback/read-cljs-string "#piggieback.test/lstr \"gaol@en-uk\""))))))
