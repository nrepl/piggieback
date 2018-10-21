[![Build Status](https://travis-ci.org/nrepl/piggieback.png?branch=master)](http://travis-ci.org/nrepl/piggieback)
[![codecov](https://codecov.io/gh/nrepl/piggieback/branch/master/graph/badge.svg)](https://codecov.io/gh/nrepl/piggieback)
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

Piggieback is compatible with Clojure 1.8.0+, and _requires_ ClojureScript
`1.9` or later and nREPL `0.2.10` or later.

**Please, note that Piggieback 0.3.7 is the first version compatible
with nREPL 0.4+.**

### Leiningen

These instructions are for Leiningen. Translating them for use in boot should be
straightforward.

Modify your `project.clj` to include the following `:dependencies` and
`:repl-options`:

```clojure
:profiles {:dev {:dependencies [[cider/piggieback "0.3.10"]]
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

**The instructions below require nREPL 0.4.4 or newer**

Add this alias to `~/.clojure/deps.edn`:

``` clojure
{
;; ...
:aliases {:nrepl
          {:extra-deps
            {nrepl/nrepl {:mvn/version "0.4.4"}
             cider/piggieback {:mvn/version "0.3.10"}}}}
}
```

Then you can simply run a ClojureScript capable nREPL like this:

``` shell
clj -R:nrepl -m nrepl.cmdline --middleware "[cider.piggieback/wrap-cljs-repl]"
```

Afterwards simply connect to the running server with your favourite
nREPL client (e.g. CIDER).

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

```
$ lein repl
....
user=> (require 'cljs.repl.nashorn)
nil
user=> (cider.piggieback/cljs-repl (cljs.repl.nashorn/repl-env))
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
Nashorn, Node, and browser REPLs.

*Support for Rhino was dropped in version 0.3. All users of Rhino are
advised to switch to using Nashorn instead.*

## Acknowledgements

[Nelson Morris](http://twitter.com/xeqixeqi) was instrumental in the initial
development of piggieback.

## Need Help?

Send a message to the
[clojure-tools](http://groups.google.com/group/clojure-tools) mailing list, or
ping `@bhauman` or `@bbatsov` on the Clojurians Slack or Twitter if you
have questions or would like to contribute patches.

## License

Copyright © 2012-2018 Chas Emerick, Bruce Hauman, Bozhidar Batsov and other contributors.

Distributed under the Eclipse Public License, the same as Clojure.

[vim-fireplace]: https://github.com/tpope/vim-fireplace
[Cursive]: https://cursive-ide.com/
[CIDER]: https://github.com/clojure-emacs/CIDER
[cider-nrepl]: https://github.com/clojure-emacs/cider-nrepl
[refactor-nrepl]: https://github.com/clojure-emacs/refactor-nrepl
[CCW]: https://github.com/ccw-ide/ccw
