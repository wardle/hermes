(ns com.eldrix.hermes.ecl-bench
  "Regression-oriented ECL benchmarks. Covers distinct ECL execution paths
  through both `expand-ecl` (stored-fields Result path) and `ecl->concept-ids`
  (concept-id-only projection). `ecl-count` shares the concept-ids projection
  and is not benched separately."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hermes.core :as hermes]
            [criterium.core :as crit]))

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (try (f)
         (finally (hermes/close *svc*)))))

(use-fixtures :once live-test-fixture)

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
    :ecl   "< 763158003 : { << 127489000 = << 372687004 }"}
   {:title "member-filter-prefix: refset members with mapTarget prefix"
    :ecl   "^ 447562003 {{ M mapTarget = \"G\" }}"}])

(deftest ^:benchmark bench-ecl
  (doseq [{:keys [title ecl]} shapes]
    (println (str "\n*** " title))
    (println "  expand-ecl:")
    (crit/quick-bench (doall (hermes/expand-ecl *svc* ecl)))
    (println "  ecl->concept-ids:")
    (crit/quick-bench (hermes/ecl->concept-ids *svc* ecl))))

(comment
  (clojure.test/run-tests))
