# CHANGELOG

## `0.2.2`

* Removed superfluous Clojure 1.6.0 dependency (gh-70)
* The current nREPL's session's `*e` binding is now set properly when an
  uncaught exception occurs.

## `0.2.1`

Fixes nREPL load-file support, implementing it in terms of evaluation of the
`load-file` `cljs.repl` special function.

## `0.2.0`

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
