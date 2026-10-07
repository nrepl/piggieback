(ns cider.test-data-readers
  "Trivial data readers used by the #128 regression tests. Kept dependency-free
  because it is required early, while Clojure loads data_readers.cljc.")

(defn read-lstr [s]
  [::lstr s])

(defn read-lstr-cljs [s]
  [::lstr-cljs s])
