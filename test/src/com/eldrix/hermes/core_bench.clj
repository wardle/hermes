(ns com.eldrix.hermes.core-bench
  (:require [clojure.test :refer [deftest run-tests use-fixtures]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.search :as search]
            [criterium.core :as crit :refer [quick-bench]]))

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (f)
    (hermes/close *svc*)))

(use-fixtures :once live-test-fixture)

(deftest ^:benchmark bench-extended-concept
  (println "\n*** Benchmarking hermes/extended-concept")
  (quick-bench
    (hermes/extended-concept *svc* 24700007)))

(deftest ^:benchmark bench-make-extended-descriptions
  (println "\n*** Benchmarking search/make-extended-descriptions")
  (quick-bench
    (search/make-extended-descriptions (.-store *svc*) (hermes/concept *svc* 24700007))))

(deftest ^:benchmark bench-all-parents
  (println "\n*** Benchmarking hermes/all-parents")
  (quick-bench
    (hermes/all-parents *svc* 24700007)))

(comment
  (run-tests)
  (def ^:dynamic *svc* (hermes/open "snomed.db"))
  (crit/quick-bench
    (search/make-extended-descriptions (.-store *svc*) (hermes/concept *svc* 24700007))))

