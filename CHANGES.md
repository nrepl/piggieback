# CHANGELOG

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
