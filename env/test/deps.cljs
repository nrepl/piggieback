;; A foreign lib for the test suite, standing in for the cljsjs packages that
;; real projects pull in via their own deps.cljs.
{:foreign-libs [{:file "piggieback_test/foreign_lib.inc.js"
                 :provides ["piggieback-test.foreign-lib"]}]}
