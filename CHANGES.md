# CHANGELOG

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
