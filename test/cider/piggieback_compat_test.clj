(ns cider.piggieback-compat-test
  (:require
   [clojure.test :refer [deftest is]]
   [cider.piggieback.compat :as compat]
   [nrepl.transport :as transport]))

(deftest nrepl-1-3+?-is-a-boolean
  ;; Whatever nREPL version we're running against, the detection must resolve to
  ;; a concrete boolean (never nil), since it gates a `when-not`.
  (is (boolean? compat/nrepl-1-3+?)))

(deftest output-bindings-routes-out-and-err
  (let [msg {:id "1"
             :transport (reify transport/Transport
                          (recv [this] this)
                          (recv [this _timeout] this)
                          (send [this _resp] this))}
        bindings (compat/output-bindings msg)]
    ;; All supported nREPL versions ship the print middleware, so we expect a
    ;; binding map rather than nil.
    (is (some? bindings))
    (is (contains? bindings #'*out*))
    (is (contains? bindings #'*err*))
    (is (instance? java.io.Writer (get bindings #'*out*)))
    (is (instance? java.io.Writer (get bindings #'*err*)))))
