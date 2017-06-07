# Piggieback [![Travis CI status](https://secure.travis-ci.org/cemerick/piggieback.png?branch=master)](http://travis-ci.org/#!/cemerick/piggieback/builds)

[nREPL](http://github.com/clojure/tools.nrepl) middleware that enables the
use of a ClojureScript REPL on top of an nREPL session.

<!--
#### **Wait!**

Are you just getting started with ClojureScript or using ClojureScript
in a REPL? You should almost certainly be starting with
[Austin](https://github.com/cemerick/austin); it uses Piggieback, but wraps it
up with a bunch of helpful utilities, auto-configuration of your `project.clj`,
and other goodies.
-->

## Why?

Two reasons:

* The default ClojureScript REPL (as described in the
["quick start"](https://github.com/clojure/clojurescript/wiki/Quick-Start)
tutorial) assumes that it is running in a teletype environment. This works fine
with nREPL tools in that environment (e.g. `lein repl` in `Terminal.app` or
`gnome-terminal`, etc), but isn't suitable for development environments that
have richer interaction models (including editors like vim [fireplace] and emacs
[CIDER] and IDEs like Intellij [Cursive] and Eclipse [Counterclockwise]).
* Most of the more advanced tool support for Clojure and ClojureScript (code
  completion, introspection and inspector utilities, refactoring tools, etc) is
  packaged and delivered as nREPL extensions.

Piggieback provides an alternative ClojureScript REPL entry point
(`cemerick.piggieback/cljs-repl`) that changes an nREPL session into a
ClojureScript REPL for `eval` and `load-file` operations, while accepting all
the same options as `cljs.repl/repl`. When the ClojureScript REPL is terminated
(by sending `:cljs/quit` for evaluation), the nREPL session is restored to it
original state.

## "Installation"

These instructions are for Leiningen. Translating them for use in boot should be
straightforward.

Piggieback is compatible with Clojure 1.6.0+, and _requires_ ClojureScript
`0.0-3165` or later and nREPL `0.2.10` or later.

Modify your `project.clj` to include the following `:dependencies` and
`:repl-options`:

```clojure
:profiles {:dev {:dependencies [[com.cemerick/piggieback "0.2.2"]
                                [org.clojure/tools.nrepl "0.2.10"]]
                 :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
```

The `:repl-options` bit causes `lein repl` to automagically mix the Piggieback
nREPL middleware into its default stack. (Yes, you need to explicitly declare a
local nREPL dependency to use piggieback, due to a
[Leiningen bug](https://github.com/technomancy/leiningen/issues/1771).)

_If you're using Leiningen directly, or as the basis for the REPLs in your local
development environment (e.g. CIDER, fireplace, counterclockwise, etc), you're
done._ [Skip to starting a ClojureScript REPL](#usage).

If you're not starting nREPL through Leiningen (e.g. maybe you're starting up
an nREPL server from within an application), you can achieve the same thing by
specifying that the `wrap-cljs-repl` middleware be mixed into nREPL's default
handler:

```clojure
(require '[clojure.tools.nrepl.server :as server]
         '[cemerick.piggieback :as pback])

(server/start-server
  :handler (server/default-handler #'pback/wrap-cljs-repl)
  ; ...additional `start-server` options as desired
  )
```

(Alternatively, you can add `wrap-cljs-repl` to your application's hand-tweaked
nREPL handler.  Keep two things in mind when doing so:

* Piggieback needs to be "above" nREPL's
  `clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval`; it
  doesn't use `interruptible-eval`'s evaluation machinery, but it does reuse its
  execution queue and thus inherits its interrupt capability.
* Piggieback depends upon persistent REPL sessions, like those provided by
  `clojure.tools.nrepl.middleware.session/session`.)

## Usage

```
$ lein repl
....
user=> (cemerick.piggieback/cljs-repl (cljs.repl.rhino/repl-env))
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
`cemerick.piggieback/cljs-repl`? After that point, all expressions sent to the
REPL are evaluated within the ClojureScript environment.
`cemerick.piggieback/cljs-repl`'s passes along all of its options to
`cljs.repl/repl`, so all of the tutorials and documentation related to it hold
(including the
[ClojureScript Quick Start tutorial](https://github.com/clojure/clojurescript/wiki/Quick-Start)).

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
the Rhino, Nashorn, Node, and browser REPLs.

## Acknowledgements

[Nelson Morris](http://twitter.com/xeqixeqi) was instrumental in the initial
development of piggieback.

## Need Help?

Send a message to the
[clojure-tools](http://groups.google.com/group/clojure-tools) mailing list, or
ping `cemerick` on freenode irc or [twitter](http://twitter.com/cemerick) if you
have questions or would like to contribute patches.

## License

Copyright © 2012-2015 Chas Emerick and other contributors.

Distributed under the Eclipse Public License, the same as Clojure.
