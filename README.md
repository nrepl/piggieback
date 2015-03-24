# Piggieback [![Travis CI status](https://secure.travis-ci.org/cemerick/piggieback.png)](http://travis-ci.org/#!/cemerick/piggieback/builds)

[nREPL](http://github.com/clojure/tools.nrepl) middleware that enables the
use of a ClojureScript REPL on top of an nREPL session.

#### **Wait!**

Are you just getting started with ClojureScript or using ClojureScript
in a REPL? You should almost certainly be starting with
[Austin](https://github.com/cemerick/austin); it uses Piggieback, but wraps it
up with a bunch of helpful utilities, auto-configuration of your `project.clj`,
and other goodies.

## Why?

The default ClojureScript REPL (as described in the ["quick
start"](https://github.com/clojure/clojurescript/wiki/Quick-Start) tutorial)
requires/assumes that it is running in a teletype environment; specifically:

* that code to be evaluated is provided via `*in*`, and that results are
  primarily printed to `*out*`
* that every REPL evaluation is performed in the same thread as that which
  started the REPL

nREPL does not provide such guarantees, and so starting the default
ClojureScript REPL in an nREPL session without the benefit of teletype support
(which exists in e.g. `lein repl`, but not in other, non-terminal Clojure
tooling) yields errors and/or lost output instead of a usable REPL.

Piggieback provides an alternative ClojureScript REPL entry point
(`cemerick.piggieback/cljs-repl`) that changes an nREPL session into a
ClojureScript REPL for `eval` and `load-file` operations, while accepting all
the same options as `cljs.repl/repl`. When the ClojureScript REPL is terminated
(by sending `:cljs/quit` for evaluation), the nREPL session is restored to it
original state.

## "Installation"

Piggieback is available in Maven Central. Add this `:dependency` to your
Leiningen `project.clj`:

```clojure
[com.cemerick/piggieback "0.2.0-SNAPSHOT"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>com.cemerick</groupId>
  <artifactId>piggieback</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Piggieback is compatible with Clojure 1.6.0+, and _requires_ ClojureScript
`0.0-3148` or later and nREPL `0.2.9` or later.

## Changelog

Available @
[CHANGES.md](https://github.com/cemerick/piggieback/blob/master/CHANGES.md).

## Usage

Piggieback is nREPL middleware, so you need to add it to your nREPL server's
middleware stack.

If you're using Leniningen v2.0+, you can add this to your `project.clj` to
automagically mix the Piggieback middleware into the stack that `lein repl` will
use when starting nREPL:

```clojure
:repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
```

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

With Piggieback added to your nREPL middleware stack, you can start a
ClojureScript REPL from any nREPL-capable client (e.g.
[Leiningen](http://leiningen.org),
[REPL-y](https://github.com/trptcolin/reply/),
[Counterclockwise](http://code.google.com/p/counterclockwise/),
[CIDER](https://github.com/clojure-emacs/cider),
[Cursive](http://cursiveclojure.com/), and so on).

### At the REPL

```
$ lein repl
....
user=> (cemerick.piggieback/cljs-repl (cljs.repl.rhino/repl-env))
Type `:cljs/quit` to stop the ClojureScript REPL
nil
cljs.user=> (+ 1 1)
2
cljs.user=> (defn <3 [a b] (str a " <3 " b "!"))
#<
function _LT_3(a, b) {
    return [cljs.core.str(a), cljs.core.str(" <3 "), cljs.core.str(b), cljs.core.str("!")].join("");
}
>
nil
cljs.user=> (<3 "nREPL" "ClojureScript")
"nREPL <3 ClojureScript!"
cljs.user=> 
```

Notice that the REPL prompt changed after invoking
`cemerick.piggieback/cljs-repl`? After that point, all expressions sent to the
REPL are evaluated within the ClojureScript environment.  Of course, you can
concurrently take advantage of all of nREPL's other facilities, including
connecting to the server with other clients (so as to easily modify Clojure and
ClojureScript code in the same JVM), and interrupting hung ClojureScript
invocations:

```clojure
cljs.user=> (iterate inc 0)
^C
cljs.user=> "Error evaluating:" (iterate inc 0) :as "cljs.core.iterate.call(null,cljs.core.inc,0);\n"
java.lang.ThreadDeath
        java.lang.Thread.stop(Thread.java:776)
        clojure.tools.nrepl.middleware.interruptible_eval$interruptible_eval$fn__374.invoke(interruptible_eval.clj:185)
        cemerick.piggieback$wrap_cljs_repl$fn__2535.invoke(piggieback.clj:171)
        clojure.tools.nrepl.middleware.pr_values$pr_values$fn__390.invoke(pr_values.clj:17)
        clojure.tools.nrepl.middleware.session$add_stdin$fn__451.invoke(session.clj:185)
        clojure.tools.nrepl.middleware.session$session$fn__444.invoke(session.clj:164)
        clojure.tools.nrepl.server$handle_STAR_.invoke(server.clj:16)
        clojure.tools.nrepl.server$handle.invoke(server.clj:25)
        clojure.tools.nrepl.server$accept_connection$fn__458.invoke(server.clj:35)
        clojure.core$binding_conveyor_fn$fn__3989.invoke(core.clj:1819)
        clojure.lang.AFn.call(AFn.java:18)
        java.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:303)
        java.util.concurrent.FutureTask.run(FutureTask.java:138)
        java.util.concurrent.ThreadPoolExecutor$Worker.runTask(ThreadPoolExecutor.java:886)
        java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:908)
        java.lang.Thread.run(Thread.java:680)
InterruptedException   java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireInterruptibly (AbstractQueuedSynchronizer.java:1199)
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
