# Piggieback

[nREPL](http://github.com/clojure/tools.nrepl) middleware that enables the
bootstrap of a ClojureScript REPL on top of an nREPL session.

## Why?

The default ClojureScript REPL (as described in the ["quick
start"](https://github.com/clojure/clojurescript/wiki/Quick-Start) tutorial)
requires/assumes that it is running in a terminal environment; specifically:

* that code to be evaluated is provided via `*in*`, and that results are
* primarily printed to `*out*` that every REPL evaluation is performed in the
* _same_ thread as that which started the REPL

nREPL does not provide for such things, and so starting the default
ClojureScript REPL in an nREPL session yields a massive pile of `No Context
associated with current Thread` errors, and resulting exceptions attempting to
evaluate `core.cljs` and co.

Piggieback provides an alternative ClojureScript REPL entry point
(`cemerick.piggieback/cljs-repl`) that lifts a ClojureScript REPL on top of any
nREPL session, while accepting all the same options as `cljs.repl/repl`.

## Installation

Piggieback is available in Maven Central. Add this `:dependency` to your Leiningen
`project.clj`:

```clojure
[com.cemerick/piggieback "0.0.2"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>com.cemerick</groupId>
  <artifactId>piggieback</artifactId>
  <version>0.0.2</version>
</dependency>
```

Piggieback is compatible with Clojure 1.4.0+.

## Usage

Piggieback is nREPL middleware, so you need to add it to your nREPL server's
middleware stack.

If you're using recent Leniningen previews (**v2.0-preview9** is the minimum
required to use this configuration), you can add
this to your `project.clj` to automagically mix the Piggieback middleware into
the stack that `lein repl` will use when starting nREPL:

```clojure
:injections [(require 'cemerick.piggieback)]
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

* Piggieback needs to be "above" something that can evaluate code, like
  `clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval`.
* Piggieback depends upon persistent REPL sessions, like those provided by
  `clojure.tools.nrepl.middleware.session/session`.)

With Piggieback added to your nREPL middleware stack, you can start a
ClojureScript REPL from any nREPL-capable client (e.g.
[Leiningen](http://leiningen.org),
[REPL-y](https://github.com/trptcolin/reply/),
[Counterclockwise](http://code.google.com/p/counterclockwise/),
[nrepl.el](https://github.com/kingtim/nrepl.el), and so on).

### Rhino ClojureScript Environment (default)

So, with Leiningen, and using the Rhino ClojureScript environment (the default
that you get when you e.g. run the `script/repljs` script in the ClojureScript
source tree, or follow the ['Quick Start' ClojureScript
tutorial](https://github.com/clojure/clojurescript/wiki/Quick-Start)):

```clojure
la-mer:piggieback chas$ lein2 repl
nREPL server started on port 56393
REPL-y 0.1.0-beta10
Clojure 1.4.0
    Exit: Control+D or (exit) or (quit)
Commands: (user/help)
    Docs: (doc function-name-here)
          (find-doc "part-of-name-here")
  Source: (source function-name-here)
          (user/sourcery function-name-here)
 Javadoc: (javadoc java-object-or-class-here)
Examples from clojuredocs.org: [clojuredocs or cdoc]
          (user/clojuredocs name-here)
          (user/clojuredocs "ns-here" "name-here")
user=> (cemerick.piggieback/cljs-repl)
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

Note that the REPL prompt changed after invoking
`cemerick.piggieback/cljs-repl`; after that point, all expressions sent to the
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

### Browser REPL

Piggieback can be used with other ClojureScript REPL environments as well, such
as browser-repl.  For example, start with this `index.html` file in your
project (by the way, nearly all of this is cribbed from the [browser REPL
tutorial](https://github.com/clojure/clojurescript/wiki/The-REPL-and-Evaluation-Environments):

```html
<html>
  <head>
    <meta charset="UTF-8">
    <title>Browser-connected REPL through nREPL with Piggieback</title>
  </head>
  <body>
    <div id="content">
      <script type="text/javascript" src="out/goog/base.js"></script>
      <script type="text/javascript" src="piggieback_browser.js"></script>
      <input type="text" name="afield" id="afield"/>      
      <script type="text/javascript">
        goog.require('piggieback_browser');
      </script>
    </div>
  </body>
</html>
```

Now create `piggieback_browser.cljs`:

```clojure
(ns piggieback-browser
  (:require [clojure.browser.repl :as repl]))
(repl/connect "http://localhost:9000/repl")
```

…and compile it:

```
$CLOJURESCRIPT/bin/cljsc piggieback_browser.cljs > piggieback_browser.js
```

Now start your ClojureScript REPL through Piggieback; but, this time, use the
browser-repl environment instead of the Rhino default:

```clojure
(require 'cljs.repl.browser)

(cemerick.piggieback/cljs-repl
  :repl-env (doto (cljs.repl.browser/repl-env :port 9000)
              cljs.repl/-setup))
```

Now go to [http://localhost:9000](http://localhost:9000); note that you *must* access the
`index.html` page you created earlier through the same server that it will
connect to the browser-repl through due to the JavaScript "same-origin" policy.
Doing anything else (such as opening the `index.html` file from disk directly)
will _not_ work, and will probably result in your ClojureScript/nREPL
session being hung permanently with no feedback.

Once the browser-repl is connected, we evaluate ClojureScript expressions in the
browser:

```clojure
=> (set! (.-value (goog.dom/getElement "afield")) "it works!")
"it works!"
=> (.-value (goog.dom/getElement "afield"))
"it works!"
```

…and so on.

(Huge thanks to [Nelson Morris](http://twitter.com/xeqixeqi) for doing the
initial experimentation with Piggieback + browser-repl, and for helping me
puzzle out all sorts of ClojureScript difficulty I had along the way.)

## Need Help?

Send a message to the [clojure-tools](http://groups.google.com/group/clojure-tools)
mailing list, or ping `cemerick` on freenode irc or 
[twitter](http://twitter.com/cemerick) if you have questions
or would like to contribute patches.

## License

Copyright © 2012 Chas Emerick and other contributors.

Distributed under the Eclipse Public License, the same as Clojure.
