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

;; A reader declared with a reader conditional must resolve to its :cljs branch,
;; as it does in ClojureScript's own REPL. The :clj branch typically returns a
;; JVM object, which the compiler then fails to emit as a constant. ClojureScript
;; only reads data_readers.cljc with the :cljs feature since 1.11.
(deftest cljs-branch-of-data-readers-is-used
  (when (resolve 'cljs.analyzer/load-data-readers)
    (env/with-compiler-env (env/default-compiler-env)
      (binding [ana/*cljs-ns* 'cljs.user]
        (is (= [:cider.test-data-readers/lstr-cljs "gaol@en-uk"]
               (cider.piggieback/read-cljs-string "#piggieback.test/cljs-lstr \"gaol@en-uk\"")))))))
