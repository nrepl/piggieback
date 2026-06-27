(ns cider.test-data-readers
  "A trivial data reader used by the #128 regression test. Kept dependency-free
  because it is required early, while Clojure loads data_readers.cljc.")

(defn read-lstr [s]
  [::lstr s])
