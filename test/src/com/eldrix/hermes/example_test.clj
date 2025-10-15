(ns com.eldrix.hermes.example-test
  "Integration test that imports example-snapshot and verifies correct import."
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [com.eldrix.hermes.core :as hermes])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- delete-recursively
  [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- submap? [m1 m2]
  (set/subset? (set m1) (set m2)))

(deftest test-import-example-snapshot
  (testing "Import example-snapshot and verify component counts"
    (let [temp-dir (str (Files/createTempDirectory "hermes-test" (into-array FileAttribute [])))]
      (try
        (hermes/import-snomed temp-dir [(io/resource "example-snapshot")])
        (hermes/index temp-dir)
        (let [{:keys [components]} (hermes/status temp-dir {:counts? true})]
          (is (submap? {:concepts 4 :descriptions 6 :relationships 4 :concrete-values 4} components))
          (is (submap? {:descriptions-concept 6 :component-refsets 12 :members-search 12} (:indices components))))
        (finally
          (delete-recursively temp-dir))))))
