(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'clojure.test/is}
  :within-depth 1
  :reason "The `is` macro commonly expands to contain an `if` with a condition that is a constant."})

(disable-warning
 {:linter :unused-ret-vals-in-try
  :if-inside-macroexpansion-of #{'cemerick.piggieback/with-rhino-context}
  :within-depth 3
  :reason "The `with-rhino-context` macro intentionally discards ` (. org.mozilla.javascript.Context enter)`"})

(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'cemerick.piggieback-test/eastwood-ignore-unused-ret}
  :within-depth 3
  :reason "The macro wraps expressions that are used solely for side-effects in the repl session."})
