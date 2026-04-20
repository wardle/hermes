(ns com.eldrix.hermes.ecl-bench
  "Benchmarks for ECL expansion and constrained search.

  Each shape exercises a different ECL execution path; for each shape we
  benchmark raw ECL expansion plus autocomplete-style searches using a few
  representative terms (rare/medium/common) so regressions that favour one
  term frequency can be spotted."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hermes.core :as hermes]
            [criterium.core :as crit]))

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (try (f)
         (finally (hermes/close *svc*)))))

(use-fixtures :once live-test-fixture)

(def ^:private search-terms ["pain" "scler" "heart"])

(def ^:private shapes
  [{:title "hier-small: << 24700007 (Multiple sclerosis subtree)"
    :ecl   "<< 24700007"}
   {:title "hier-large: << 404684003 (Clinical finding subtree)"
    :ecl   "<< 404684003"}
   {:title "simple-refinement: finding with associatedMorphology = edema"
    :ecl   "< 404684003 : 116676008 = 79654002"}
   {:title "any-value: finding with any associatedMorphology"
    :ecl   "< 404684003 : 116676008 = *"}
   {:title "any-attribute: wildcard attribute, specific value"
    :ecl   "< 404684003 : * = 79654002"}
   {:title "ungrouped-neq: substance not of type disorder (ungrouped)"
    :ecl   "< 763158003 : << 127489000 != << 372687004"}
   {:title "grouped-neq: same as ungrouped-neq but grouped"
    :ecl   "< 763158003 : { << 127489000 != << 372687004 }"}
   {:title "grouped-eq: grouped = for comparison with grouped-neq"
    :ecl   "< 763158003 : { << 127489000 = << 372687004 }"}])

(deftest ^:benchmark bench-ecl
  (doseq [{:keys [title ecl]} shapes]
    (println (str "\n*** " title))
    (println "  expand-ecl:")
    (crit/quick-bench (doall (hermes/expand-ecl *svc* ecl)))
    (doseq [term search-terms]
      (println (str "  search \"" term "\" (max-hits 100):"))
      (crit/quick-bench
        (hermes/search *svc* {:s term :constraint ecl :max-hits 100})))))

(comment
  (clojure.test/run-tests))
