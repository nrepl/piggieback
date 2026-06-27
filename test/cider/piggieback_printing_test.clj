(ns cider.piggieback-printing-test
  "Unit tests for how piggieback prints ClojureScript evaluation results that the
  Clojure reader can't read back directly. These need no JavaScript runtime."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.reader.edn :as edn]))

(require 'cider.piggieback)

(defn- read-as-unknown
  "Read `s` the way `do-eval` reads a ClojureScript result, i.e. with unknown
  tagged literals turned into `UnknownTaggedLiteral`."
  [s]
  (edn/read-string {:default (resolve 'cider.piggieback/->UnknownTaggedLiteral)} s))

;; Regression test for https://github.com/nrepl/piggieback/issues/120
(deftest unknown-tagged-literal-round-trips
  (testing "string data keeps its quotes and is separated from the tag"
    (is (= "#time/time \"10:11:12\"" (pr-str (read-as-unknown "#time/time \"10:11:12\"")))))
  (testing "collection and map data are printed readably"
    (is (= "#foo/bar [1 2 3]" (pr-str (read-as-unknown "#foo/bar [1 2 3]"))))
    (is (= "#my/thing {:a 1}" (pr-str (read-as-unknown "#my/thing {:a 1}")))))
  (testing "nested unknown tagged literals round-trip too"
    (is (= "#a/x [#b/y \"z\"]" (pr-str (read-as-unknown "#a/x [#b/y \"z\"]"))))))
