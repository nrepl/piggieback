[![CircleCI](https://circleci.com/gh/nrepl/piggieback/tree/master.svg?style=svg)](https://circleci.com/gh/nrepl/piggieback/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/cider/piggieback.svg)](https://clojars.org/cider/piggieback)

# Piggieback

[nREPL](http://github.com/nrepl/nrepl) middleware that enables the
use of a ClojureScript REPL on top of an nREPL session.

## Why?

Two reasons:

* The default ClojureScript REPL (as described in the
["quick start"](https://clojurescript.org/guides/quick-start)
tutorial) assumes that it is running in a teletype environment. This works fine
with nREPL tools in that environment (e.g. `lein repl` in `Terminal.app` or
`gnome-terminal`, etc), but isn't suitable for development environments that
have richer interaction models (including editors like vim ([vim-fireplace][]) and Emacs
([CIDER][]), and IDEs like Intellij ([Cursive][]) and Eclipse ([Counterclockwise][CCW])).

* Most of the more advanced tool support for Clojure and ClojureScript (code
  completion, introspection and inspector utilities, refactoring tools, etc) is
  packaged and delivered as nREPL extensions (e.g. [cider-nrepl][] and [refactor-nrepl][]).

Piggieback provides an alternative ClojureScript REPL entry point
(`cider.piggieback/cljs-repl`) that changes an nREPL session into a
ClojureScript REPL for `eval` and `load-file` operations, while accepting all
the same options as `cljs.repl/repl`. When the ClojureScript REPL is terminated
(by sending `:cljs/quit` for evaluation), the nREPL session is restored to it
original state.

## Installation

Piggieback is compatible with Clojure 1.10.0+, and _requires_ ClojureScript
`1.10` or later and nREPL `1.0.0` or later.

To use the default Node.js REPL (`cljs.repl.node`) you'll also need to install a recent version of Node.js.

### Leiningen

These instructions are for Leiningen. Translating them for use in Boot should be
straightforward.

Modify your `project.clj` to include the following `:dependencies` and
`:repl-options`:

```clojure
:profiles {:dev {:dependencies [[cider/piggieback "0.6.0"]]
                 :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}}
```

The `:repl-options` bit causes `lein repl` to automagically mix the Piggieback
nREPL middleware into its default stack.

_If you're using Leiningen directly, or as the basis for the REPLs in your local
development environment (e.g. CIDER, fireplace, counterclockwise, etc), you're
done._ [Skip to starting a ClojureScript REPL](#usage).

### Boot

Contributions welcome!

### Clojure CLI (aka `tools.deps`)

**The instructions below require nREPL 1.0.0 or newer**

Add this alias to `~/.clojure/deps.edn`:

``` clojure
{
;; ...
:aliases {:nrepl
          {:extra-deps
            {nrepl/nrepl {:mvn/version "1.3.0"}
             cider/piggieback {:mvn/version "0.6.0"}}}}
}
```

Then you can simply run a ClojureScript-capable nREPL server like this:

``` shell
clj -R:nrepl -m nrepl.cmdline --middleware "[cider.piggieback/wrap-cljs-repl]"
```

When you connect to the running server with your favourite nREPL client
(e.g. CIDER), you will be greeted by a Clojure REPL. Within this Clojure REPL,
you can now [start a ClojureScript REPL](#usage).

### Embedded

If you're not starting nREPL through a build tool (e.g. maybe you're starting up
an nREPL server from within an application), you can achieve the same thing by
specifying that the `wrap-cljs-repl` middleware be mixed into nREPL's default
handler:

```clojure
(require '[nrepl.server :as server]
         '[cider.piggieback :as pback])

(server/start-server
  :handler (server/default-handler #'pback/wrap-cljs-repl)
  ; ...additional `start-server` options as desired
  )
```

Alternatively, you can add `wrap-cljs-repl` to your application's hand-tweaked
nREPL handler.  Keep two things in mind when doing so:

* Piggieback needs to be "above" nREPL's
  `nrepl.middleware.interruptible-eval/interruptible-eval`; it
  doesn't use `interruptible-eval`'s evaluation machinery, but it does reuse its
  execution queue and thus inherits its interrupt capability.
* Piggieback depends upon persistent REPL sessions, like those provided by
  `nrepl.middleware.session/session`.)


## Usage

Before you run the following, you must have gone through the [setup
steps](#installation). Instead of using `lein repl`, you might also connect to a
headless nREPL using your development environment.

```
$ lein repl
....
user=> (require 'cljs.repl.node)
nil
user=> (cider.piggieback/cljs-repl (cljs.repl.node/repl-env))
To quit, type: :cljs/quit
nil
cljs.user=> (defn <3 [a b] (str a " <3 " b "!"))
#<
function cljs$user$_LT_3(a, b) {
    return [cljs.core.str(a), cljs.core.str(" <3 "), cljs.core.str(b), cljs.core.str("!")].join("");
}
>
cljs.user=> (<3 "nREPL" "ClojureScript")
"nREPL <3 ClojureScript!"
```

See how the REPL prompt changed after invoking
`cider.piggieback/cljs-repl`? After that point, all expressions sent to the
REPL are evaluated within the ClojureScript environment.
`cider.piggieback/cljs-repl`'s passes along all of its options to
`cljs.repl/repl`, so all of the tutorials and documentation related to it hold.

*Important Notes*

1. When using Piggieback to enable a browser REPL: the ClojureScript compiler
   defaults to putting compilation output in `out`, which is probably not where
   your ring app is serving resources from (`resources`,
   `target/classes/public`, etc). Either configure your ring app to serve
   resources from `out`, or pass a `cljs-repl` `:output-dir` option so that a
   reasonable correspondence is established.
2. The `load-file` nREPL operation will only load the state of files from disk.
   This is in contrast to "regular" Clojure nREPL operation, where the current
   state of a file's buffer is loaded without regard to its saved state on disk.

Of course, you can concurrently take advantage of all of nREPL's other
facilities, including connecting to the same nREPL server with other clients (so
as to easily modify Clojure and ClojureScript code via the same JVM), and
interrupting hung ClojureScript invocations:

```clojure
cljs.user=> (iterate inc 0)
^C
cljs.user=> "Error evaluating:" (iterate inc 0) :as "cljs.core.iterate.call(null,cljs.core.inc,0);\n"
java.lang.ThreadDeath
        java.lang.Thread.stop(Thread.java:776)
		....
cljs.user=> (<3 "nREPL still" "ClojureScript")
"nREPL still <3 ClojureScript!"
```

(The ugly `ThreadDeath` exception will be eliminated eventually.)

Piggieback works well with all known ClojureScript REPL environments, including
Node and browser REPLs.

*Support for Rhino was dropped in version 0.3, and Nashorn support
was dropped from ClojureScript in 1.10.741.*

## Design

This section documents some of the main design decisions in Piggieback
and the differences between similar functionality in nREPL and Piggieback.

Perhaps the most important thing to remember is that Piggieback is written in
Clojure and runs on Clojure. It drives ClojureScript evaluation by using
ClojureScript's Clojure API (`cljs.repl/IJavaScriptEnv`).  This allows you to
host both Clojure and ClojureScript evaluation sessions on the same nREPL
server, which is pretty cool. On the other hand it also means that you can't use
Piggieback with self-hosted ClojureScript REPLs (e.g. Lumo).

**Note:** For self-hosted ClojureScript you'll need an nREPL implementation that can run
natively on it (e.g. [nrepl-cljs](https://github.com/djblue/nrepl-cljs)).

### No hard dependency on ClojureScript

Piggieback doesn't have a hard dependency on ClojureScript, as users are
expected to provide the necessary ClojureScript dependency themselves. If
ClojureScript is not present, Piggieback simply won't do anything (see
`piggieback_shim.clj` for details).

This allows tools to safely load Piggieback without
having to consider whether something would blow up.

### Session type based dispatch

Clients don't have to specify explicitly whether they are doing a ClojureScript eval
operation (e.g. by passing some `:env :cljs` request params). As Piggieback operates
at the nREPL session level all clients need to do is to pass a Piggieback session
to ops like `eval` and that would trigger the Piggieback version of those ops.

### Evaluation

As noted above Piggieback provides alternative versions of the standard nREPL
ops `eval` and `load-file` for ClojureScript evaluation. Due to some differences
between Clojure and ClojureScript they don't behave exactly the same.

Most notably - for performance reasons we don't spin separate instances of `cljs.repl`
for each evaluation, as nREPL does for Clojure. In practice this means that if you try
to evaluate multiple forms together only the first of them would be evaluated:

```clojure
;; standard ClojureScript REPL behaviour
cljs.user>
(declare is-odd?)
(defn is-even? [n] (if (= n 0) true (is-odd? (dec n))))
(defn is-odd? [n] (if (= n 0) false (is-even? (dec n))))
#'cljs.user/is-odd?
#'cljs.user/is-even?
#'cljs.user/is-odd?
cljs.user> (is-even? 4)
true
```

Let's compare this to a REPL powered by Piggieback:

```clojure
cljs.user>
(declare is-odd?)
(defn is-even? [n] (if (= n 0) true (is-odd? (dec n))))
(defn is-odd? [n] (if (= n 0) false (is-even? (dec n))))
#'cljs.user/is-odd?
cljs.user> (is-even? 4)
Compile Warning   <cljs repl>   line:1  column:2

  Use of undeclared Var cljs.user/is-even?

  1  (is-even? 4)
      ^---

#object[TypeError TypeError: Cannot read property 'call' of undefined]
	 (<NO_SOURCE_FILE>)
cljs.user>
```

Normally that's not a big deal in practice, as you'd rarely want to evaluate multiple expressions together, but it's
something to be kept in mind.

**Note:** Check out [this discussion](https://github.com/nrepl/piggieback/pull/98) for more details on the subject.

### Pretty-printing

**Note:** Piggieback introduced support for nREPL's pretty-printing interface
in version 0.5.

Support for pretty printing ClojureScript evaluation results is not
entirely straightforward. This is because Piggieback mostly relies on
the underlying nREPL server implementation to support the features of
the nREPL protocol and on the `cljs.repl/IJavaScriptEnv` interface for
ClojureScript evaluation.

nREPL 0.6 introduced `nrepl.middleware.print` to facilitate printing
evaluation results in a configurable way. Since nREPL is implemented
in Clojure and runs on the JVM, the middleware relies on receiving
Clojure values for printing them. Conversely when evaluating a
ClojureScript expression in a JavaScript environment, the resulting
Clojure value of the evaluation is always a string. If this value
would simply be passed on as is to the middleware, only the string
itself could be printed by it instead of the evaluation result within
the string.

There are multiple approaches for working around this issue with
various trade-offs. The current implementation has the following main
considerations:

1. `nrepl.middleware.print` is used to print ClojureScript evaluation
   results whenever possible, so that the same nREPL (pretty) printing
   configuration is applied to both Clojure and ClojureScript.

2. For cases where the above is not possible (see below), there is a
   fallback to support basic pretty printing.

In order to support `nrepl.middleware.print` for ClojureScript
evaluation results, they first need to be _read_. The resulting
Clojure values can then be normally printed by the middleware. However
there are various cases where ClojureScript evaluation results can not
be read by the default Clojure reader. Some examples:

- Functions: `#object[Function]`
- Objects: `#object[cljs.user.Cheese]`, `#object[Window [object Window]]`
- `#js` literals: `#js {:foo 1, :bar 2}`
- `#queue` literals: `#queue [1 2 3]`
- Custom tagged literals: `#user/cheese "Pálpusztai"`
- Types implementing `IPrintWithWriter` in a way that is incompatible
  with the Clojure reader

To work around some of these cases Piggieback provides its own
`UnknownTaggedLiteral` type. It is used as the default tag reader when
reading ClojureScript evaluation results. It doesn't parse the
contents of the literal and has `print-method` defined to simply print
the original.

**Note:** When a pretty-printer which doesn't rely on `print-method` to
serialize values (such as fipp, puget, etc.) is used,
`UnknownTaggedLiteral` will be serialized in the output instead of the
original literal.

There are still cases left which can prevent the Clojure reader from
successfully reading ClojureScript evaluation results (mostly custom
`IPrintWithWriter` implementations). In order to support pretty
printing these results as well, the ClojureScript expression to be
evaluated is always wrapped with `cljs.pprint/pprint` (unless
`:nrepl.middleware.print/print` is set to `nrepl.util.print/pr` or `cider.nrepl.pprint/pr`, in
which case `cljs.core/pr` is used instead). This means that whenever
the Clojure reader fails to read the value for any reason, we can
safely fall back on an already (pretty) printed string, albeit
disabling `nrepl.middleware.print` and hence effectively ignoring the
`nrepl.middleware.print` configuration. Special care is taken that
output written to `*out*` during evaluation is not affected by the
wrapping.

For the cases where the (pretty) printing configuration is not being
applied, the reader probably failed to read the evaluation results and
the above fallbacks are being used instead.

**Note:** See [this pull request](https://github.com/nrepl/piggieback/pull/108)
for more background and discussion on the current solution.

## FAQ

### Why "piggieback" instead of "piggyback"?

That's one of life's greatest mysteries. Only Chas can answer that one.

### Why is the artifact group id "cider" instead of "nrepl"?

Bozhidar took over the maintenance of Piggieback before taking over
the maintenance of nREPL. That's why for a period of time Piggieback lived under
CIDER's GitHub org and back then it made sense to use CIDER's group id.
Eventually, it got reunited with nREPL, but we've opted to preserve
the CIDER group id to avoid further breakages.

For the same reason the main namespace is `cider.piggieback` instead of
`nrepl.piggieback`.

### Does Piggieback work with self-hosted ClojureScript REPLs (e.g. Lumo)?

No, it doesn't. Piggieback is implemented in Clojure and relies on Clojure's ClojureScript evaluation
API (`cljs.repl/IJavaScriptEnv`).

For self-hosted ClojureScript you'll need a native ClojureScript nREPL implementation like
[nrepl-cljs](https://github.com/djblue/nrepl-cljs).

### Does shadow-cljs use Piggieback?

No, it doesn't use it. It's most recommended for shadow-cljs users to avoid including the `cider.piggieback/wrap-cljs-repl` middleware.

Unlike `figwheel`, which relies on Piggieback, `shadow-cljs` provides
its own nREPL middleware. That's why some features of Piggieback (e.g. pretty-printing)
might not be available with `shadow-cljs`.

You can find `shadow-cljs`'s middleware [here](https://github.com/thheller/shadow-cljs/blob/faab284fe45b04328639718583a7d70feb613d26/src/main/shadow/cljs/devtools/server/nrepl.clj).

## Need Help?

Feel free to create a Github issue or ask on `#cider` on Clojurians Slack if you
have questions or would like to contribute patches.

## Acknowledgements

[Nelson Morris](http://twitter.com/xeqixeqi) was instrumental in the initial
development of piggieback.

## License

Copyright © 2012-2023 Chas Emerick, Bruce Hauman, Bozhidar Batsov and other contributors.

Distributed under the Eclipse Public License, the same as Clojure.

[vim-fireplace]: https://github.com/tpope/vim-fireplace
[Cursive]: https://cursive-ide.com/
[CIDER]: https://github.com/clojure-emacs/CIDER
[cider-nrepl]: https://github.com/clojure-emacs/cider-nrepl
[refactor-nrepl]: https://github.com/clojure-emacs/refactor-nrepl
[CCW]: https://github.com/ccw-ide/ccw
