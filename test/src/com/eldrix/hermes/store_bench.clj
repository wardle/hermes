(ns com.eldrix.hermes.store-bench
  "Benchmarks for the store layer."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hermes.impl.store :as store]
            [criterium.core :as crit]))

(def ^:dynamic *store* nil)

(defn live-test-fixture [f]
  (with-open [st (store/open-store "snomed.db/store.db")]
    (binding [*store* st]
      (f))))

(use-fixtures :once live-test-fixture)

;; ---------------------------------------------------------------------------
;; Single-entity lookups
;; ---------------------------------------------------------------------------

(deftest ^:benchmark bench-concept-lookup
  (println "\n*** concept lookup")
  (crit/quick-bench
    (store/concept *store* 24700007)))

(deftest ^:benchmark bench-description-lookup
  (println "\n*** description lookup (concept-id + description-id)")
  (crit/quick-bench
    (store/description *store* 24700007 41398015)))

(deftest ^:benchmark bench-description-lookup-by-id
  (println "\n*** description lookup (description-id only, uses index)")
  (crit/quick-bench
    (store/description *store* 41398015)))

(deftest ^:benchmark bench-concept-descriptions
  (println "\n*** concept-descriptions (8 descriptions)")
  (crit/quick-bench
    (store/concept-descriptions *store* 24700007)))

;; ---------------------------------------------------------------------------
;; Refset operations
;; ---------------------------------------------------------------------------

(deftest ^:benchmark bench-component-refset-ids
  (println "\n*** component-refset-ids (concept with 8 refset memberships)")
  (crit/quick-bench
    (store/component-refset-ids *store* 24700007)))

(deftest ^:benchmark bench-component-refset-items
  (println "\n*** component-refset-items (description, 3 items)")
  (crit/quick-bench
    (store/component-refset-items *store* 41398015)))

(deftest ^:benchmark bench-component-refset-items-concept
  (println "\n*** component-refset-items (concept, 8 items)")
  (crit/quick-bench
    (store/component-refset-items *store* 24700007)))

(deftest ^:benchmark bench-description-refsets
  (println "\n*** description-refsets (refset items + acceptability)")
  (crit/quick-bench
    (store/description-refsets *store* 41398015)))

(deftest ^:benchmark bench-component-in-refsets
  (println "\n*** component-in-refsets? (membership check, 2 refsets)")
  (crit/quick-bench
    (store/component-in-refsets? *store* 41398015
      [900000000000508004 999001261000000100])))

;; ---------------------------------------------------------------------------
;; Hierarchy traversal
;; ---------------------------------------------------------------------------

(deftest ^:benchmark bench-proximal-parent-ids
  (println "\n*** proximal-parent-ids (direct IS-A parents)")
  (crit/quick-bench
    (vec (store/proximal-parent-ids *store* 24700007))))

(deftest ^:benchmark bench-all-parents
  (println "\n*** all-parents (transitive closure)")
  (crit/quick-bench
    (store/all-parents *store* 24700007)))

(deftest ^:benchmark bench-parent-relationships
  (println "\n*** parent-relationships (grouped by type)")
  (crit/quick-bench
    (store/parent-relationships *store* 24700007)))

(deftest ^:benchmark bench-parent-relationships-expanded
  (println "\n*** parent-relationships-expanded (with transitive closure)")
  (crit/quick-bench
    (store/parent-relationships-expanded *store* 24700007)))

(deftest ^:benchmark bench-is-a
  (println "\n*** is-a? (subsumption check)")
  (crit/quick-bench
    (store/is-a? *store* 24700007 404684003)))

(deftest ^:benchmark bench-paths-to-root
  (println "\n*** paths-to-root")
  (crit/quick-bench
    (store/paths-to-root *store* 24700007)))

(deftest ^:benchmark bench-properties-by-group
  (println "\n*** properties-by-group")
  (crit/quick-bench
    (store/properties-by-group *store* 24700007)))

;; ---------------------------------------------------------------------------
;; Language / synonym lookups
;; ---------------------------------------------------------------------------

(deftest ^:benchmark bench-language-synonyms
  (println "\n*** language-synonyms (filter + refset membership checks)")
  (crit/quick-bench
    (vec (store/language-synonyms *store* 24700007
           [900000000000508004 999001261000000100]))))

(deftest ^:benchmark bench-preferred-synonym
  (println "\n*** preferred-synonym (2 language refsets)")
  (crit/quick-bench
    (store/preferred-synonym *store* 24700007
      [900000000000508004 999001261000000100])))

;; ---------------------------------------------------------------------------
;; Extended concept (the kitchen sink)
;; ---------------------------------------------------------------------------

(deftest ^:benchmark bench-extended-concept
  (println "\n*** extended-concept (the kitchen sink)")
  (crit/quick-bench
    (store/extended-concept *store* 24700007)))
