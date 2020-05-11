# CHANGELOG

## unreleased

## 0.5.0 (2020-05-11)

* [#108](https://github.com/nrepl/piggieback/pull/108): **(Breaking)** Dropped support for nREPL versions 0.4 and 0.5, `[nrepl "0.6.0"]` is now the minimum required version.
* [#108](https://github.com/nrepl/piggieback/pull/108): Added support for nREPL print middleware introduced in nREPL 0.6.0.
* Moved away from Nashorn: changed tests and example code to Node.

## 0.4.2 (2019-10-08)

* [#107](https://github.com/nrepl/piggieback/pull/107): Make piggieback a no-op when ClojureScript is not loaded.

## 0.4.1 (2019-05-15)

* Fix a bug affecting nREPL 0.6 where `*out*` and `*err` were not reliably bound after session init.

## 0.4.0 (2019-02-05)

* **(Breaking)** Dropped support for `clojure.tools.nrepl`. `[nrepl "0.4.0"]` is
  now the minimum required version.
* Add compatibility with nREPL 0.6+.

## 0.3.10 (2018-10-21)

* [#95](https://github.com/nrepl/piggieback/issues/95): Bind `*cljs-warnings*`.
* [#97](https://github.com/nrepl/piggieback/pulls/97): Establish a binding to `cljs.analyzer/*unchecked-if*`.

## 0.3.9

* Honor `:repl-requires` CLJS repl option.
* Bind `cljs.repl/*repl-env*` to better support CLJS versions newer than 1.10.126.

## 0.3.8

* Fix the `tools.nrepl` support. (a silly typo had broken it)

## 0.3.7

* Add compatibility with nREPL 0.4+.

## 0.3.6

* Allow repl-options to flow through.

## 0.3.5

* Fix loss of `compiler-env`.

## 0.3.4

* Fix REPL teardown problem with ClojureScript 1.10.

## 0.3.3

* Fix REPL teardown problem and bind *out* and *err* for initialization (this affected the node repl).

## 0.3.2

* Enable `:wrap` repl-option.
* Enable `:caught` repl-option.
* Capture `cljs-warning-handlers` so consumers can bind them.

## 0.3.1

* [#87](https://github.com/nrepl/piggieback/issues/87): Fix a Nashorn regression introduced in 0.2.3.

## 0.3.0

* Drop support for Rhino.
* Change the namespace prefix from `cemerick` to `cider`.

## 0.2.3

* Changed the artefact coordinates to `cider/piggieback`. It's now being deployed
to Clojars, instead of to Maven Central.
* [#80](https://github.com/nrepl/piggieback/pull/80): Make eval just eval, instead of creating a new REPL for each evaluation.
* Piggieback now requires ClojureScript 1.9 and Java 8.

## 0.2.2

* Removed superfluous Clojure 1.6.0 dependency (gh-70)
* The current nREPL's session's `*e` binding is now set properly when an
  uncaught exception occurs.

## 0.2.1

Fixes nREPL load-file support, implementing it in terms of evaluation of the
`load-file` `cljs.repl` special function.

## 0.2.0

This release is essentially a rewrite to accommodate the significant changes to
the upstream ClojureScript REPL infrastructure. Using piggieback is effectively
unchanged, things just work a lot better now (and many outstanding issues are no
longer relevant due to a change in how Piggieback is implemented).

Note that `cemerick.piggieback/cljs-repl` has been changed to match the signature
provided by `cljs.repl/repl`, i.e. the REPL environment is always the first
argument.

There are no breaking changes AFAICT w.r.t. other nREPL middlewares that might use
Piggieback to access e.g. the current session's ClojureScript REPL environment, etc.

## [`0.1.5`](https://github.com/cemerick/piggieback/issues?q=milestone%3A0.1.5+is%3Aclosed)

* Add support for "new style" ClojureScript special REPL functions. Piggieback
  is now completely compatible with ClojureScript >= 2665. (gh-38)
* Fix to support ClojureScript-provided node.js REPL environment (gh-39)

## [`0.1.4`](https://github.com/cemerick/piggieback/issues?q=milestone%3A0.1.4+is%3Aclosed)

* Change to support updated `cljs.repl` API, per
  https://github.com/clojure/clojurescript/wiki/Custom-REPLs. Piggieback now
  requires ClojureScript >= 2665.

## [`0.1.3`](https://github.com/cemerick/piggieback/issues?milestone=3&state=closed)

* Piggieback now uses tools.reader to read expressions sent for evaluation when
  a ClojureScript REPL environment is active. This preserves proper source
  information (useful for source maps) and allows Piggieback to participate in
  the aliasing mechanism used in ClojureScript to support namespace
  alias-qualified keywords e.g. `::alias/keyword` (gh-19)
* The ClojureScript support for the `load-file` nREPL operation now correctly
  provides the source file's path instead of its name to the compiler. (gh-24)

## `0.1.2`

Released to fix a derp in `0.1.1`.

## `0.1.1`

* Adds support for ClojureScript compiler environments introduced in `0.0-2014`.
  Now requires that version of ClojureScript or higher.

## `0.1.0`

* _Breaking change_: ClojureScript REPL environments no longer need to / should
  be explicitly `-setup` prior to use with `cemerick.piggieback/cljs-repl`.
  i.e. this:

  ```
(cemerick.piggieback/cljs-repl
  :repl-env (doto (create-some-cljs-repl-env)
              cljs.repl/-setup))
```
should be replaced with this:
```
(cemerick.piggieback/cljs-repl :repl-env (create-some-cljs-repl-env))
```
Fixes gh-10.
* Deprecated `cemerick.piggieback/rhino-repl-env`, which will be removed
  ~`0.2.0`; it now simply calls through
  to `cljs.repl.rhino/repl-env`.  Any usage of the former should be replaced
  with the latter.  Fixes gh-9.
