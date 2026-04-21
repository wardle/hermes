(ns com.eldrix.hermes.ecl-test
  "Every `deftest` here is marked `^:live` — including parser and
  constraint-satisfaction tests that don't themselves touch the store.
  `live-test-fixture` is a `:once` fixture that opens `snomed.db`; if any
  test in this namespace runs, the fixture fires. Without a database (CI
  via `clj -M:test -e :live`) every test must be filtered out, so the tag
  applies to the whole namespace. Pure parser tests that should run in CI
  belong in a separate namespace without this fixture."
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is use-fixtures run-tests testing]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.ecl :as ecl]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [instaparse.core :as insta]))

(stest/instrument)

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (f)
    (hermes/close *svc*)))

(use-fixtures :once live-test-fixture)

(def ecl-wildcard-cases
  ;; [pattern input expected-match?]
  [;; basic wildcards
   ["cardi*opathy"       "cardiomyopathy"       true]
   ["cardi*opathy"       "cardiomyopathy extra"  false]
   ["multiple sclero*"   "multiple sclerosis"    true]
   ["multiple sclero*"   "sclerosis multiple"    false]
   ["*sclero*"           "multiple sclerosis"    true]
   ["sclero*"            "multiple sclerosis"    false]
   ["*"                  "anything"              true]
   ;; ? is literal in ECL, not a single-char wildcard
   ["hep?titis"          "hep?titis"             true]
   ["hep?titis"          "hepatitis"             false]
   ;; escape sequences
   ["test\\*"            "test*"                 true]
   ["test\\*"            "testing"               false]
   ["path\\\\name"       "path\\name"            true]
   ["quoted\\\"term"     "quoted\"term"          true]
   ;; regex special characters are literal
   ["test (finding)"     "test (finding)"        true]
   ["test [a]"           "test [a]"              true]
   ["a+b"                "a+b"                   true]
   ["a+b"                "aab"                   false]])

(deftest ^:live ecl-wildcard-pattern
  (doseq [[pattern input expected] ecl-wildcard-cases]
    (testing (str "wild:\"" pattern "\" vs \"" input "\"")
      (is (= expected (boolean (re-matches (ecl/ecl-wildcard->pattern pattern) input)))))))

(def ^:live simple-tests
  [{:ecl "404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{404684003})))}
   {:ecl "<  24700007"
    :f   (fn [concept-ids]
           (is (not (concept-ids 24700007)) "descendant of should not include concept itself")
           (is (concept-ids 426373005) "relapsing remitting MS (426373005) should be a type of MS (24700007)"))}
   {:ecl "<<  73211009 |Diabetes mellitus|"
    :f   (fn [concept-ids]
           (is (concept-ids 73211009) "descendent-or-self-of should include concept itself")
           (is (concept-ids 46635009) "type 1 diabetes mellitus (46635009) should be a type of diabetes mellitus (73211009)"))}
   {:ecl "<!  404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (not (concept-ids 404684003)) "'child of' should not include concept itself")
           (is (concept-ids 64572001) "'child of' clinical finding should include 'disease (64572001)")
           (is (not (concept-ids 24700007)) "'child' of' should include only proximal relationships"))}
   {:ecl "    >  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (concept-ids 40541001)) "'ancestor of' should not include concept itself")
           (is (concept-ids 19829001) "ancestors of acute pulmonary oedema should include 'disorder of lung' (19829001)"))}
   {:ecl "    >!  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (concept-ids 40541001)) "'parent of' should not include concept itself")
           (is (concept-ids 19242006) "pulmonary oedema should be a proximal parent of acute pulmonary oedema")
           (is (not (concept-ids 19829001)) "(proximal) parent of acute pulmonary oedema should not include 'disorder of lung' (19829001)"))}
   {:ecl "24700007 |Multiple sclerosis| AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (empty? concept-ids) "multiple sclerosis should not be in lateralizable body structure refset"))}
   {:ecl "(24136001 |Hip joint| OR 53120007|Arm|) AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{24136001 53120007}) "both 'hip joint' and 'arm' should be in lateralizable body structure refset"))}
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  =  79654002 |Edema|"
    :f   (fn [concept-ids]
           (let [morphologies (->> concept-ids
                                   (map #(hermes/parent-relationships-of-type *svc* % snomed/AssociatedMorphology))
                                   (map set))]
             (is (every? true? (map #(contains? % 79654002) morphologies))) ;; all must have EXACTLY oedema as morphology
             (is (every? false? (map #(contains? % 85628007) morphologies)))))} ;; and *not* a subtype of oedema such as chronic oedema
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  = <<  79654002 |Edema|"
    :f   (fn [concept-ids]
           (is (concept-ids 40541001)))}          ;; acute pulmonary oedema has morphology 'acute oedema' and should be included via this expression
   {:ecl "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|"
    :as  :extended-concept
    :f   (fn [ec]
           (is (store/is-a? nil ec 404684003))              ;; are all a clinical finding?
           (is (store/has-property? nil ec 363698007 39057004)) ;; are all affecting the pulmonary value?
           (is (store/has-property? nil ec 116676008 415582006)))} ;; are all a stenosis?
   {:ecl " * :  246075003 |Causative agent|  =  387517004 |Paracetamol|"
    :as  :extended-concept
    :f   (fn [ec] (is (store/has-property? nil ec 246075003 387517004)))}

   ;; attribute groups
   {:ecl "<  404684003 |Clinical finding| :
           {  363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| ,
              116676008 |Associated morphology|  = <<  415582006 |Stenosis| },
           {  363698007 |Finding site|  = <<  53085002 |Right ventricular structure| ,
              116676008 |Associated morphology|  = <<  56246009 |Hypertrophy| }"
    :f   (fn [concept-ids]
           (is (concept-ids 86299006)))}          ;; this should find tetralogy of Fallot

   ;; attribute constraint operators
   {:ecl "  <<  404684003 |Clinical finding| :\n        <<  47429007 |Associated with|  = <<  267038008 |Edema|"
    :as  :extended-concept
    :f   (fn [{prels :parentRelationships :as ec}]
           (testing (get-in ec [:concept :id])
             (is (contains? (get prels snomed/IsA) 404684003))
             (let [vs (reduce (fn [_ v] (when-let [result (get prels v)] (reduced result))) nil (hermes/all-children *svc* 47429007))]
               (is (contains? vs 267038008)))))}

   {:ecl "<<  404684003 |Clinical finding| :\n        >>  246075003 |Causative agent|  = <<  267038008 |Edema|"
    :as :extended-concept
    :f (fn [{prels :parentRelationships :as ec}]
         (testing (get-in ec [:concept :id])
           (is (contains? (get prels snomed/IsA) 404684003))
           (let [vs (reduce (fn [_ v] (when-let [result (get prels v)] (reduced result))) nil (hermes/all-parents *svc* 246075003))]
             (is (contains? vs 267038008)))))}

   ;; products with one, two, three active ingredients
   {:ecl "<  373873005 |Pharmaceutical / biologic product| :\n        [1..3]  127489000 |Has active ingredient|  = <  105590001 |Substance|"}

   ;; products with exactly one active ingredient
   {:ecl "   <  373873005 |Pharmaceutical / biologic product| :\n        [1..1]  127489000 |Has active ingredient|  = <  105590001 |Substance|"}

   ;; compound expression constraints
   {:ecl " <  19829001 |Disorder of lung|  AND <  301867009 |Edema of trunk|"}
   {:ecl "<  19829001 |Disorder of lung|  OR <  301867009 |Edema of trunk|"}
   {:ecl "  <  19829001 |Disorder of lung|  AND ^  700043003 |Example problem list concepts reference set|"}

   ;; these two are equivalent expressions
   {:ecl "  <  404684003 |Clinical finding| :\n          363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| ,\n          116676008 |Associated morphology|  = <<  415582006 |Stenosis|"}
   {:ecl " <  404684003 |Clinical finding| :\n          363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure|  AND\n          116676008 |Associated morphology|  = <<  415582006 |Stenosis|"}
   {:ecl "  <  404684003 |Clinical finding| :\n          116676008 |Associated morphology|  = <<  55641003 |Infarct|  OR\n          42752001 |Due to|  = <<  22298006 |Myocardial infarction|"}
   {:ecl "  <  404684003 |Clinical finding| :\n          363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure|  AND\n          116676008 |Associated morphology|  = <<  415582006 |Stenosis|  AND\n          42752001 |Due to|  = <<  445238008 |Malignant carcinoid tumor|"}
   {:ecl "   <  404684003 |Clinical finding|  :\n         ( 363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure|  AND\n           116676008 |Associated morphology|  = <<  415582006 |Stenosis| ) OR\n           42752001 |Due to|  = <<  445238008 |Malignant carcinoid tumor|"}
   {:ecl "   <  404684003 |Clinical finding| :\n         { 363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| ,\n           116676008 |Associated morphology|  = <<  415582006 |Stenosis| } OR\n         { 363698007 |Finding site|  = <<  53085002 |Right ventricular structure| ,\n           116676008 |Associated morphology|  = <<  56246009 |Hypertrophy| }"}
   {:ecl "   <<  19829001 |Disorder of lung|  MINUS <<  301867009 |Edema of trunk|"}
   {:ecl " <<  19829001 |Disorder of lung|  MINUS <<  301867009 |Edema of trunk|"}
   {:ecl "<  404684003 |Clinical finding| :  116676008 |Associated morphology|  =\n         ((<<  56208002 |Ulcer|  AND <<  50960005 |Hemorrhage| ) MINUS <<  26036001 |Obstruction| )"}
   {:ecl "< 27658006 |Amoxicillin-containing product| : << 127489000 |Has active ingredient| != << 372687004 |Amoxicillin|"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "Ungrouped != should return products with non-amoxicillin ingredients"))}

   ;; grouped concrete != — no single group has amoxicillin AND strength != 875
   {:ecl "< 27658006: { << 127489000 = << 372687004, 1142135004 != #875 }"
    :f   (fn [concept-ids]
           (is (not (concept-ids 392259005))))}
   ;; grouped concrete != — group 1 has amoxicillin with strength 875 != 125
   {:ecl "< 27658006: { << 127489000 = << 372687004, 1142135004 != #125 }"
    :f   (fn [concept-ids]
           (is (concept-ids 392259005)))}
   ;; standalone grouped != — group 2 has clavulanate, not a subtype of amoxicillin
   {:ecl "< 27658006: { << 127489000 != << 372687004 |Amoxicillin| }"
    :f   (fn [concept-ids]
           (is (concept-ids 392259005)))}

   ;; child-or-self-of and parent-or-self-of operators
   {:ecl "<<! 404684003"
    :f   (fn [concept-ids]
           (is (concept-ids 404684003) "childOrSelfOf should include the concept itself")
           (is (concept-ids 64572001) "childOrSelfOf clinical finding should include 'disease'")
           (is (not (concept-ids 24700007)) "childOrSelfOf should not include grandchildren"))}
   {:ecl "<! 404684003"
    :f   (fn [concept-ids]
           (is (not (concept-ids 404684003)) "childOf should not include the concept itself"))}
   {:ecl ">>! 40541001"
    :f   (fn [concept-ids]
           (is (concept-ids 40541001) "parentOrSelfOf should include the concept itself")
           (is (concept-ids 19242006) "parentOrSelfOf acute pulmonary oedema should include pulmonary oedema")
           (is (not (concept-ids 19829001)) "parentOrSelfOf should not include non-proximal ancestors"))}
   {:ecl ">! 40541001"
    :f   (fn [concept-ids]
           (is (not (concept-ids 40541001)) "parentOf should not include the concept itself"))}

   ;; concept active filter
   {:ecl "<< 24700007 {{ C active = true }}"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "Should find active descendants of multiple sclerosis")
           (is (concept-ids 24700007) "Should include multiple sclerosis itself"))}
   {:ecl "<< 24700007 {{ C active = false }}"
    :f   (fn [concept-ids]
           (is (empty? concept-ids) "All subtypes of multiple sclerosis should be active"))}

   ;; description type filter
   {:ecl  "24700007 {{ type = fsn }}"
    :opts #{:skip-counts}                                   ;; expand-ecl applies q-synonym so manual count from its results is 0
    :f    (fn [concept-ids] (is (concept-ids 24700007) "Multiple sclerosis should be found via its FSN"))}
   {:ecl "24700007 {{ type = syn }}"
    :f   (fn [concept-ids] (is (concept-ids 24700007) "Multiple sclerosis should be found via its synonyms"))}

   ;; description active filter
   {:ecl "24700007 {{ D active = true }}"
    :f   (fn [concept-ids] (is (concept-ids 24700007) "Multiple sclerosis should have active descriptions"))}
   {:ecl "24700007 {{ D active = 1 }}"
    :f   (fn [concept-ids] (is (concept-ids 24700007) "active = 1 should be equivalent to active = true"))}

   ;; ancestorOrSelfOf standalone (>> conceptId)
   {:ecl ">> 40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (concept-ids 40541001) "ancestorOrSelfOf should include the concept itself")
           (is (concept-ids 19829001) "ancestorOrSelfOf should include 'disorder of lung'")
           (is (concept-ids 138875005) "ancestorOrSelfOf should include root concept"))}

   ;; wildcard expressions (correctness tested in test-wildcard-equivalences)
   ;; NOTE: << *, < *, >> * are tested at the query level to avoid realising
   ;; hundreds of thousands of concept IDs into memory.

   ;; member of with nested expression constraint
   {:ecl "^ (<< 723264001 |Lateralizable body structure reference set|)"
    :f   (fn [concept-ids]
           (is (concept-ids 24136001) "memberOf with expression should find hip joint")
           (is (concept-ids 53120007) "memberOf with expression should find arm"))}

   ;; ECL comments
   {:ecl "/* a comment */ < 24700007 |Multiple sclerosis|"
    :f   (fn [concept-ids]
           (is (not (concept-ids 24700007)) "comment should not affect parsing; < should exclude self")
           (is (concept-ids 426373005) "should find relapsing remitting MS despite comment"))}

   ;; reverse flag — use small hierarchy to avoid materialising all concepts
   {:ecl "<< 372687004 |Amoxicillin| : R 127489000 |Has active ingredient| = << 763158003 |Medicinal product|"
    :f   (fn [concept-ids]
           (is (concept-ids 372687004) "amoxicillin should be an active ingredient of some medicinal product"))}

   ;; nested expression constraint — use small hierarchy to avoid materialising all clinical findings
   {:ecl "< (<< 24700007 |Multiple sclerosis|)"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "nested expression should return results")
           (is (not (concept-ids 24700007)) "outer < should exclude MS itself"))}

   ;; wildcard term filter — spec requires matching full description string
   {:ecl "<< 24700007 {{ D term = wild:\"multiple sclerosis\" }}"
    :f   (fn [concept-ids]
           (is (concept-ids 24700007) "wild without * should match the full description term"))}
   {:ecl "<< 24700007 {{ D term = wild:\"multiple sclero*\" }}"
    :f   (fn [concept-ids]
           (is (concept-ids 24700007) "multi-word wildcard should match 'Multiple sclerosis'"))}
   {:ecl "<< 24700007 {{ D term = wild:\"*sclero*\" }}"
    :f   (fn [concept-ids]
           (is (concept-ids 24700007) "leading * should permit substring matching across the whole term"))}
   {:ecl "<< 24700007 {{ D term = wild:\"sclero*\" }}"
    :f   (fn [concept-ids]
           (is (not (concept-ids 24700007)) "wild without a leading * should not match mid-term text"))}

   ;; wildcard term filter — same-description semantics
   {:ecl  "24700007 {{ D term = wild:\"multiple sclerosis (disorder)\", type = fsn }}"
    :opts #{:skip-counts}                                   ;; expand-ecl applies q-synonym so manual count from its results is 0
    :f    (fn [concept-ids]
            (is (concept-ids 24700007) "FSN wildcard should match when the same description is an FSN"))}
   {:ecl "24700007 {{ D term = wild:\"multiple sclerosis (disorder)\", type = syn }}"
    :f   (fn [concept-ids]
           (is (not (concept-ids 24700007)) "synonym type filter must not match via a different description"))}

   ;; wildcard attribute name `* = V` — projected via child-relationships index,
   ;; not as an OR over every attribute concept id.
   {:ecl "<< 19829001 |Disorder of lung| : * = 79654002 |Edema morphology|"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "wildcard attribute should find at least one disorder-of-lung concept with edema as a value"))}
   {:ecl "< 19829001 |Disorder of lung| : * = << 79654002 |Edema morphology|"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "wildcard attribute with a hierarchy value should return results"))}
   {:ecl "< 19829001 |Disorder of lung| : * = (79654002 |Edema morphology| OR 85628007 |Chronic edema|)"
    :f   (fn [concept-ids]
           (is (seq concept-ids) "wildcard attribute with a disjunction value should return results"))}
   {:ecl "< 19829001 |Disorder of lung| : * = 100000102"
    :f   (fn [concept-ids]
           (is (empty? concept-ids) "wildcard attribute with a non-existent value should return no results"))}])



(deftest ^:live do-simple-tests
  (doseq [{:keys [ecl as f opts] :or {as :concept-ids}} simple-tests]
    (testing ecl
      (let [ids (hermes/ecl->concept-ids *svc* ecl)]
        (when-not (:skip-counts opts)
          (let [manual-count (count (into #{} (map :conceptId) (hermes/expand-ecl *svc* ecl)))]
            (is (= manual-count (hermes/ecl-count *svc* ecl))
                "ecl-count should match the manually-deduped concept-id count from expand-ecl")))
        (when f
          (case as
            :concept-ids (f ids)
            :extended-concept (run! f (map #(hermes/extended-concept *svc* %) ids))))))))




(def pending-or-unsupported-tests
  "ECL features not yet implemented, or syntax that the current evaluator
  cannot faithfully represent. Tests pass if an exception is thrown."
  [;; concept filter: definitionStatus (from snomed.org/ecl section 6.9)
   {:ecl "< 56265001 |Heart disease| {{ C definitionStatus = primitive }}"}
   {:ecl "< 56265001 |Heart disease| {{ C definitionStatus = defined }}"}
   {:ecl "< 56265001 |Heart disease| {{ C definitionStatusId = 900000000000074008 }}"}
   ;; concept filter: moduleId (from snomed.org/ecl section 6.9)
   {:ecl "< 195967001 |Asthma| {{ C moduleId = 900000000000207008 |SNOMED CT core module| }}"}
   ;; concept filter: effectiveTime (from snomed.org/ecl section 6.9)
   {:ecl "< 125605004 |Fracture of bone| {{ C effectiveTime >= \"20190731\" }}"}
   {:ecl "< 125605004 |Fracture of bone| {{ C effectiveTime <= \"20190731\" }}"}
   ;; description filter: moduleId (from snomed.org/ecl section 6.8)
   {:ecl "< 195967001 |Asthma| {{ D moduleId = 900000000000207008 |SNOMED CT core module| }}"}
   ;; description filter: effectiveTime (from snomed.org/ecl section 6.8)
   {:ecl "< 125605004 |Fracture of bone| {{ D effectiveTime >= \"20190731\" }}"}
   ;; description filter: id (official example 8.5.1)
   {:ecl "< 131148009 |Bleeding| {{ D id = 670169018 }}"}
   ;; language filters (official examples 8.2.1 and 8.2.2)
   {:ecl "< 64572001 |Disease| {{ term = \"hjärt\", language = sv }}"}
   {:ecl "< 64572001 |Disease| {{ term = \"hjärt\", language = sv }} {{ term = \"heart\", language = en }}"}
   ;; string concrete refinement (official example 2.10)
   {:ecl "< 373873005 |pharmaceutical / biologic product| : 111115 |trade name| = \"PANADOL\""}
   ;; grouped string concrete refinement — must fail closed with a specific
   ;; reason, not an internal case miss
   {:ecl "< 373873005 : { 1142135004 = \"PANADOL\" }" :reason :string-in-group}
   {:ecl "< 373873005 : { 1142135004 != \"PANADOL\" }" :reason :string-in-group}
   ;; grouped boolean concrete refinement — must fail closed with a specific
   ;; reason, not an internal case miss
   {:ecl "< 373873005 : { 1142135004 = true }" :reason :boolean-in-group}
   {:ecl "< 373873005 : { 1142135004 != false }" :reason :boolean-in-group}
   ;; attribute group cardinality (from snomed.org/ecl section 6.3)
   {:ecl "< 373873005 |Pharmaceutical / biologic product| : [1..3] { 127489000 |Has active ingredient| = < 105590001 |Substance| }"}
   {:ecl "< 404684003 |Clinical finding| : [1..1] { 363698007 |Finding site| = < 91723000 |Anatomical structure| }"}
   {:ecl "< 404684003 |Clinical finding| : [0..0] { [2..*] 363698007 |Finding site| = < 91723000 |Anatomical structure| }"}
   ;; unsupported grouped syntax should fail closed until implemented
   {:ecl "< 404684003 : { 363698007 = << 39057004 OR 116676008 = << 415582006 }"}
   {:ecl "< 404684003 : { (363698007 = << 39057004 , 116676008 = << 415582006) }"}
   ;; cardinality + reverse flag (from snomed.org/ecl section 6.3)
   {:ecl "< 105590001 |Substance| : [3..3] R 127489000 |Has active ingredient| = *"}
   ;; reverse flag inside an attribute group — cannot be faithfully represented
   {:ecl "<< 27658006 : { R 116676008 = 79654002 }"}
   ;; refset field selection (from snomed.org/ecl section 6.6)
   {:ecl " ^ [targetComponentId]  900000000000527005 |SAME AS association reference set|  {{ M referencedComponentId =  67415000 |Hay asthma|  }}"}
   ;; wildcard ancestor/parent (from snomed.org/ecl section 6.1)
   {:ecl "> *"}
   {:ecl ">! *"}
   ;; boolean concrete refinement (official example 2.11)
   {:ecl "< 373873005 |Pharmaceutical / biologic product| : 859999999102 |Is in national benefit scheme| = TRUE"}
   ;; [0..0] cardinality with != (requires universal quantification, from snomed.org/ecl section 6.5)
   {:ecl "< 404684003 |Clinical finding| : [0..0] 116676008 |Associated morphology| != << 26036001 |Obstruction|"}
   ;; alt identifiers (from snomed.org/ecl section 6.1)
   {:ecl "LOINC#54486-6"}])

(deftest ^:live do-pending-or-unsupported-tests
  (doseq [{:keys [ecl reason]} pending-or-unsupported-tests]
    (testing ecl
      (let [ex (try (hermes/expand-ecl *svc* ecl) nil
                    (catch Exception e e))]
        (is (some? ex)
            (str "Expected exception for unimplemented or unsupported feature: " ecl))
        (when reason
          (is (= reason (:reason (ex-data ex)))
              (str "Expected ex-data :reason " reason " for: " ecl)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Tests needing special treatment that do not fit into 'simple-tests' or 'pending-or-unsupported-tests'.
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:live test-equivalence
  (let [r1 (hermes/expand-ecl *svc* " < ( 125605004 |Fracture of bone| . 363698007 |Finding site| )")
        r2 (hermes/expand-ecl *svc* "<  272673000 |Bone structure|")]
    (is (= r1 r2))))


(deftest ^:live test-history
  (let [r1 (set (hermes/expand-ecl *svc* "<<  195967001 |Asthma|"))
        r2 (set (hermes/expand-ecl-historic *svc* "<<  195967001 |Asthma|"))
        r3 (set (hermes/expand-ecl *svc* "<< 195967001 {{+HISTORY}}"))]
    (is (< (count r1) (count r2)))
    (is (= r2 r3))
    (is (set/subset? r1 r2))))

(def member-filter-parser
  "A specialised instance of a parser using the ECL grammar for testing member filters."
  (insta/parser ecl/ecl-grammar :start :memberFilter :output :hiccup))

(deftest ^:live parse-member-filter
  (doseq [[s expected]
          [["mapPriority = #1"                 :memberFieldFilter]
           ["active = true"                    :activeFilter]
           ["active = 0"                       :activeFilter]
           ["moduleId = <<  32570231000036109" :moduleFilter]
           ["effectiveTime >= \"20210731\""    :effectiveTimeFilter]]]
    (let [p (member-filter-parser s)]
      (is (= expected (get-in p [1 0]))))))

(def member-filter-tests
  [{:ecl  " ^  447562003 |ICD-10 complex map reference set|  {{ M mapTarget = \"J45.9\" }}"
    :incl #{195967001 707447008 401193004}}])

(def member-filter-tests-uk
  [{:ecl  " ^  999002271000000101  {{ M mapTarget = \"J458\" }}"
    :incl #{707444001 707447008}}])

(deftest ^:live test-icd10-map-installed
  (let [installed (hermes/installed-reference-sets *svc*)]
    (is (or (contains? installed 447562003)
            (contains? installed 999002271000000101))
        "At least one ICD-10 complex map reference set must be installed (international: 447562003 or UK: 999002271000000101)")))

(deftest ^:live test-member-filter
  (when (contains? (hermes/installed-reference-sets *svc*) 447562003)
    (dorun (->> (hermes/expand-ecl *svc* " ^  447562003 |ICD-10 complex map reference set|  {{ M mapPriority = #1, mapTarget = \"J45.9\" }}")
                (map :conceptId)
                (map #(hermes/component-refset-items *svc* % 447562003))
                (map #(map :mapTarget %))
                (map #(some (fn [s] (.startsWith "J45.9" s)) %))
                (map #(is (true? %)))))
    (doseq [{:keys [ecl incl]} member-filter-tests]
      (let [concept-ids (hermes/ecl->concept-ids *svc* ecl)]
        (when incl (is (set/subset? incl concept-ids)))))))

(deftest ^{:live true :uk true} test-member-filter-uk
  (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
    (dorun (->> (hermes/expand-ecl *svc* " ^  999002271000000101  {{ M mapPriority = #1, mapTarget = \"J458\" }}")
                (map :conceptId)
                (map #(hermes/component-refset-items *svc* % 999002271000000101))
                (map #(map :mapTarget %))
                (map #(some (fn [s] (.startsWith s "J458")) %))
                (map #(is (true? %)))))
    (doseq [{:keys [ecl incl]} member-filter-tests-uk]
      (let [concept-ids (hermes/ecl->concept-ids *svc* ecl)]
        (when incl (is (set/subset? incl concept-ids)))))))

(deftest ^{:live true :uk true} test-member-filter-conjunction
  (testing "Comma-separated member filters within {{ }} are conjunctive (AND)"
    (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
      (let [;; single filter: mapTarget only
            ecl-target " ^ 999002271000000101 {{ M mapTarget = \"J458\" }}"
            ;; conjunctive filter: both conditions must hold on same member item
            ecl-both " ^ 999002271000000101 {{ M mapPriority = #1, mapTarget = \"J458\" }}"
            ids-target (hermes/ecl->concept-ids *svc* ecl-target)
            ids-both (hermes/ecl->concept-ids *svc* ecl-both)]
        (is (set/subset? ids-both ids-target)
            "Conjunctive filter results must be a subset of single-filter results")
        (is (< (count ids-both) (count ids-target))
            "Adding mapPriority constraint should reduce result count")
        ;; conjunctive filter must not return a massively over-broad result set
        ;; (without AND semantics, the mapPriority=1 filter alone would match
        ;; hundreds of thousands of member items from the entire reference set)
        (is (< (count ids-both) (* 2 (count ids-target)))
            "Conjunctive filter should not return vastly more results than a single filter")
        ;; verify every result actually has a member item satisfying BOTH conditions
        (doseq [cid ids-both]
          (let [items (hermes/component-refset-items *svc* cid 999002271000000101)]
            (is (some (fn [{:keys [mapPriority mapTarget]}]
                        (and (= 1 mapPriority)
                             (.startsWith ^String mapTarget "J458")))
                      items)
                (str "Concept " cid " must have a member item with mapPriority=1 AND mapTarget starting with J458"))))
        ;; verify no false positives: concepts matching mapTarget alone but NOT
        ;; the conjunction should be excluded
        (let [excluded (set/difference ids-target ids-both)]
          (doseq [cid excluded]
            (let [items (hermes/component-refset-items *svc* cid 999002271000000101)]
              (is (not (some (fn [{:keys [mapPriority mapTarget]}]
                               (and (= 1 mapPriority)
                              (.startsWith ^String mapTarget "J458")))
                             items))
                  (str "Concept " cid " should NOT have a member item with mapPriority=1 AND mapTarget starting with J458")))))))))

(deftest ^{:live true :uk true} test-member-filter-example-shapes
  (testing "Official examples 10.1.2 and 10.1.3: numeric and wildcard member fields"
    (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
      (let [refset-id  999002271000000101
            exact-ecl  " ^ 999002271000000101 {{ M mapGroup = #1, mapPriority = #3, mapTarget = \"J458\" }}"
            ranged-ecl " ^ 999002271000000101 {{ M mapGroup != #2, mapPriority < #3, mapTarget = wild:\"J45*\" }}"
            exact-ids  (hermes/ecl->concept-ids *svc* exact-ecl)
            ranged-ids (hermes/ecl->concept-ids *svc* ranged-ecl)]
        (is (seq exact-ids) "Expected UK map members matching the exact-shape example")
        (is (seq ranged-ids) "Expected UK map members matching the numeric/wildcard example")
        (doseq [cid exact-ids]
          (let [items (hermes/component-refset-items *svc* cid refset-id)]
            (is (some (fn [{:keys [mapGroup mapPriority mapTarget]}]
                        (and (= 1 mapGroup)
                             (= 3 mapPriority)
                             (= "J458" mapTarget)))
                      items)
                (str "Concept " cid " must have a member item with mapGroup=1, mapPriority=3 and mapTarget=J458"))))
        (doseq [cid ranged-ids]
          (let [items (hermes/component-refset-items *svc* cid refset-id)]
            (is (some (fn [{:keys [mapGroup mapPriority mapTarget]}]
                        (and (not= 2 mapGroup)
                             (< mapPriority 3)
                             (.startsWith ^String mapTarget "J45")))
                      items)
                (str "Concept " cid " must have a member item with mapGroup!=2, mapPriority<3 and mapTarget starting J45"))))))))

(deftest ^:live test-member-filter-module-id-not-equals
  (testing "moduleId != should exclude members from the specified module"
    (let [refset-id 900000000000527005
          module-id 900000000000207008]
      (when (contains? (hermes/installed-reference-sets *svc*) refset-id)
        (let [ids-eq  (hermes/ecl->concept-ids *svc*
                        (str " ^ " refset-id " {{ M moduleId = " module-id " }}"))
              ids-neq (hermes/ecl->concept-ids *svc*
                        (str " ^ " refset-id " {{ M moduleId != " module-id " }}"))]
          (is (seq ids-eq) "Expected at least one member in the specified module")
          (doseq [cid (take 200 ids-eq)]
            (let [items (hermes/component-refset-items *svc* cid refset-id)]
              (is (some #(= module-id (:moduleId %)) items)
                  (str "Concept " cid " should have a member in module " module-id))))
          ;; Intl edition has no non-core members of this refset, so `!=`
          ;; legitimately yields an empty set there.
          (when (seq ids-neq)
            (doseq [cid (take 200 ids-neq)]
              (let [items (hermes/component-refset-items *svc* cid refset-id)]
                (is (not-any? #(= module-id (:moduleId %)) items)
                    (str "Concept " cid " should not have any member in module " module-id))))
            (is (empty? (set/intersection (set (take 200 ids-eq)) (set (take 200 ids-neq))))
                "= and != moduleId filters should not overlap on sampled results")))))))

(deftest ^:live test-refinement-with-wildcard-value
  (let [ch (a/chan)]
    (a/thread (a/>!! ch (hermes/expand-ecl *svc* "<24700007: 370135005 =*")))
    (let [[v c] (a/alts!! [ch (a/timeout 200)])]
      (is (= c ch) "Timeout during expansion of ECL containing a refinement with a wildcard value")
      (let [results (->> v (map :conceptId) distinct
                         (map #(boolean (seq (hermes/parent-relationships-of-type *svc* % 370135005)))))]
        (is (seq results) "Invalid results")
        (is (every? true? results) "Invalid results")))))

(deftest ^:live test-any-value-refinement-example
  (testing "Official example 2.13: attribute = * should match concepts with at least one value"
    (let [any-value (hermes/ecl->concept-ids *svc*
                      "< 404684003 |Clinical finding| : 116676008 |Associated morphology| = *")
          cardinality (hermes/ecl->concept-ids *svc*
                        "< 404684003 |Clinical finding| : [1..*] 116676008 |Associated morphology| = *")]
      (is (seq any-value) "Any-value refinement should return clinical findings with a morphology")
      (is (= any-value cardinality)
          "attribute = * should be equivalent to requiring cardinality [1..*] for that attribute"))))

(deftest ^:live test-cardinality
  (testing "Cardinality 0..1 and 1..1"
    (let [r0-1 (hermes/expand-ecl *svc* "<<24700007: [0..1] 370135005=*") ;;370135005 = pathological process
          r1-1 (hermes/expand-ecl *svc* "<<24700007: [1..1] 370135005=*")]
      (is (seq r1-1) "Multiple sclerosis and descendants should have attribute of 'pathological process'")
      (is (>= (count r0-1) (count r1-1)) "Results for cardinality 0..1 should be the same, more than 1..1")
      (is (set/subset? (set r1-1) (set r0-1)) "Results for cardinality 1..1 should a subset of 0..1")))
  (testing "Zero cardinality"
    (let [results (hermes/expand-ecl *svc* "<<24700007: [0..0] 246075003=*")] ;; 246075003 = causative agent.
      (is (seq results) "Invalid result. Multiple sclerosis and descendants should not have attributes of 'causative agent'")))
  (testing "Many cardinality"
    (let [results (hermes/expand-ecl *svc* "<24700007: [2..*]  370135005=*")] ;; two or more pathological processes))))))
      (is (->> results
               (map :conceptId)
               (map (fn [concept-id] (count (hermes/parent-relationships-of-type *svc* concept-id 370135005))))
               (every? #(> % 1)))))))

(deftest ^:live test-term-filter
  (doseq [s ["heart att" "sjogren" "Sjögren" "heart" "hjärt"]]
    (testing (str "term filter with string: " s)
      (let [langs (hermes/match-locale *svc*)
            r1 (set (hermes/expand-ecl *svc* (str "<  64572001 |Disease|  {{ term = \"" s "\"}}")))
            r2 (set (hermes/search *svc* {:s s :language-refset-ids langs :constraint "<  64572001 |Disease| "}))
            r3 (set (hermes/expand-ecl *svc* (str "<  64572001 |Disease|  {{ term = match:\"" s "\"}}")))]
        (is (= r1 r2 r3))))))

(deftest ^:live test-term-filter-set-forms
  (testing "Official examples 8.1.5 and 8.1.7: term filter sets should behave like unions"
    (let [term-set  (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = (\"heart\" \"card\") }}")
          heart     (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = \"heart\" }}")
          card      (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = \"card\" }}")
          mixed-set (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = (match:\"gas\" wild:\"*itis\") }}")
          gas       (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = match:\"gas\" }}")
          itis      (hermes/ecl->concept-ids *svc*
                      "< 64572001 |Disease| {{ term = wild:\"*itis\" }}")]
      (is (seq gas) "term = match:\"gas\" must return results for the mixed-set check to be meaningful")
      (is (seq itis) "term = wild:\"*itis\" must return results for the mixed-set check to be meaningful")
      (is (= term-set (set/union heart card))
          "String term sets should match the union of their individual term filters")
      (is (= mixed-set (set/union gas itis))
          "Mixed match:/wild: term sets should match the union of their individual filters"))))

(deftest ^:live test-description-type-filter-set-forms
  (testing "Official examples 8.3.4 and 8.3.5: type/typeId set forms should be equivalent"
    (let [type-set   (hermes/ecl->concept-ids *svc*
                       "< 56265001 |Heart disease| {{ term = \"heart\", type = (syn fsn) }}")
          typeid-set (hermes/ecl->concept-ids *svc*
                       "< 56265001 |Heart disease| {{ term = \"heart\", typeId = ( 900000000000013009 |Synonym| 900000000000003001 |Fully specified name| ) }}")
          syn        (hermes/ecl->concept-ids *svc*
                       "< 56265001 |Heart disease| {{ term = \"heart\", type = syn }}")
          fsn        (hermes/ecl->concept-ids *svc*
                       "< 56265001 |Heart disease| {{ term = \"heart\", type = fsn }}")]
      (is (= type-set typeid-set)
          "type and typeId set forms should identify the same concepts")
      (is (= type-set (set/union syn fsn))
          "Type sets should match the union of their individual description-type filters"))))

(deftest ^:live test-term-filter-not-equals
  (testing "!= term filter is existential across descriptions, so overlap with = is allowed"
    (let [all-diseases (hermes/ecl->concept-ids *svc* "<  64572001 |Disease|  {{ term = \"heart\" }}")
          not-heart    (hermes/ecl->concept-ids *svc* "<  64572001 |Disease|  {{ term != \"heart\" }}")
          overlap      (clojure.set/intersection all-diseases not-heart)]
      (is (seq all-diseases) "There should be diseases matching 'heart'")
      (is (seq not-heart) "There should be diseases NOT matching 'heart'")
      (is (seq overlap)
          "Concepts may satisfy both term = and term != via different descriptions")
      (let [descriptions (hermes/descriptions *svc* (first overlap))
            active-terms (->> descriptions
                              (filter :active)
                              (map :term))]
        (is (some #(.contains (.toLowerCase ^String %) "heart") active-terms)
            "An overlapping concept should have an active description containing 'heart'")
        (is (some #(not (.contains (.toLowerCase ^String %) "heart")) active-terms)
            "An overlapping concept should also have an active description not containing 'heart'")))))

(deftest ^:live test-ecl-parses
  (is (hermes/valid-ecl? "<  64572001 |Disease|  {{ term = \"heart\" }}"))
  (is (hermes/valid-ecl? "<  64572001 |Disease|  {{ term = \"hjärt\" }}")
      "2-byte UTF-8 in terms")
  (is (hermes/valid-ecl? "<  64572001 |Disease|  {{ term = \"麻疹\" }}")
      "3-byte UTF-8 in terms"))

(deftest ^:live test-top-of-set
  (let [concept-ids (hermes/ecl->concept-ids *svc* " <  386617003 |Digestive system finding|  .  363698007 |Finding site|")
        r1 (hermes/ecl->concept-ids *svc* " !!> ( <  386617003 |Digestive system finding|  .  363698007 |Finding site|  )")
        r2 (hermes/ecl->concept-ids *svc* " ( <  386617003 |Digestive system finding|  .  363698007 |Finding site|  )
                                   MINUS < ( <  386617003 |Digestive system finding|  .  363698007 |Finding site|  )")
        r3 (store/top-leaves (.-store *svc*) concept-ids)]  ;; calculate in a different way, albeit slower, to check result
    (is (= r1 r2) "top of set !!> operator should be equivalent to removing descendants via a MINUS clause")
    (is (= r1 r3) "top of set !!> operator in ECL should return same concept ids as store/top-leaves")))

(deftest ^:live test-bottom-of-set
  (let [concept-ids (hermes/ecl->concept-ids *svc* "!!< ( >>  45133009 |Neurotoxic shellfish poisoning|  AND ^  991411000000109 |Emergency care diagnosis simple reference set|)")
        r1 (hermes/ecl->concept-ids *svc* "!!< ( >>  45133009 |Neurotoxic shellfish poisoning| AND ^991411000000109 |Emergency care diagnosis simple reference set|)")
        minus-concept-ids (hermes/ecl->concept-ids *svc* "> (>> 45133009 |Neurotoxic shellfish poisoning|  AND ^  991411000000109 |Emergency care diagnosis simple reference set|)")
        r2 (set/difference concept-ids minus-concept-ids)
        r3 (store/leaves (.-store *svc*) concept-ids)]
    (is (= r1 r2 r3) "bottom of set !!< operator in ECL should return same concept ids as store/leaves")))

(defn some-concrete-value?
  "Returns whether a concept's concrete values match the value specified."
  ([concept-id value]
   (->> (store/concrete-values (:store *svc*) concept-id)
        (some #(= value (:value %)))))
  ([concept-id type-id value]
   (->> (store/concrete-values (:store *svc*) concept-id)
        (filter #(= type-id (:typeId %)))
        (some #(= value (:value %))))))

(deftest ^:live test-concrete
  (let [r1 (hermes/expand-ecl *svc* "< 763158003 |Medicinal product (product)| :
     411116001 |Has manufactured dose form (attribute)|  = <<  385268001 |Oral dose form (dose form)| ,
    {    <<  127489000 |Has active ingredient (attribute)|  = <<  372687004 |Amoxicillin (substance)| ,
          1142135004 |Has presentation strength numerator value (attribute)|  = #250,
         732945000 |Has presentation strength numerator unit (attribute)|  =  258684004 |milligram (qualifier value)|}")
        r2 (hermes/expand-ecl *svc* "*: 1142135004 = #250000")]
    (is (pos? (count r1)) "No results found for ECL expression containing concrete values")
    (is (pos? (count r2)) "No results found for ECL expression containing concrete values")
    (is (every? true? (map #(some-concrete-value? (:conceptId %) 1142135004 "#250") r1)))
    (is (every? true? (map #(some-concrete-value? (:conceptId %) 1142135004 "#250000") r2)))))

(deftest ^:live test-concrete-comparison-operators
  (let [s-eq  (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 = #250 }")
        s-neq (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 != #250 }")
        s-gt  (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 > #250 }")
        s-lt  (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 < #250 }")
        s-gte (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 >= #250 }")
        s-lte (hermes/ecl->concept-ids *svc*
                 "< 27658006: { << 127489000 = << 372687004, 1142135004 <= #250 }")]
    (testing "All operators return results"
      (is (seq s-eq) "= #250 should return results")
      (is (seq s-neq) "!= #250 should return results")
      (is (seq s-gt) "> #250 should return results")
      (is (seq s-lt) "< #250 should return results")
      (is (seq s-gte) ">= #250 should return results")
      (is (seq s-lte) "<= #250 should return results"))
    (testing "Set relationships between operators"
      (is (= s-gte (set/union s-gt s-eq)) ">= should equal union of > and =")
      (is (= s-lte (set/union s-lt s-eq)) "<= should equal union of < and =")
      (is (empty? (set/intersection s-gt s-lt)) "> and < should be disjoint")
      (is (empty? (set/intersection s-gt s-eq)) "> and = should be disjoint")
      (is (empty? (set/intersection s-lt s-eq)) "< and = should be disjoint"))
    (testing "Concrete value verification for = results"
      (is (every? #(some-concrete-value? % 1142135004 "#250") s-eq)
          "All = #250 results should have concrete value #250 for type 1142135004")))
  (testing "Decimal concrete values"
    ;; 62.5mg is a clavulanate strength, which appears within co-amoxiclav
    ;; (amoxicillin-containing) products, so this narrower base still exercises
    ;; decimal parsing.
    (let [results (hermes/expand-ecl *svc* "< 27658006: 1142135004 = #62.5")]
      (is (seq results) "= #62.5 should return results for products with that strength")
      (is (every? #(some-concrete-value? (:conceptId %) 1142135004 "#62.5") results)
          "All results should have concrete value #62.5"))))

(deftest ^:live test-concept-active-filter-aliases
  (testing "Official examples 9.4.1-9.4.4: numeric active aliases should match boolean forms"
    ;; hermes only indexes IS-A relationships for active concepts, so
    ;; `<< X {{ C active = false }}` always returns empty. The aliases test
    ;; still exercises the parser and evaluator; `active-true` carries the
    ;; non-empty precondition that gives the equivalence assertion meaning.
    (let [unfiltered     (hermes/ecl->concept-ids *svc* "<< 24700007")
          active-true    (hermes/ecl->concept-ids *svc* "<< 24700007 {{ C active = true }}")
          active-one     (hermes/ecl->concept-ids *svc* "<< 24700007 {{ C active = 1 }}")
          inactive-false (hermes/ecl->concept-ids *svc* "<< 24700007 {{ C active = false }}")
          inactive-zero  (hermes/ecl->concept-ids *svc* "<< 24700007 {{ C active = 0 }}")]
      (is (seq active-true) "precondition: MS has active descendants")
      (is (= active-true active-one)
          "C active = 1 should be equivalent to C active = true")
      (is (= active-true unfiltered)
          "Active descendants filter should not narrow unfiltered descendants — hermes only indexes active IS-A")
      (is (= inactive-false inactive-zero)
          "C active = 0 should be equivalent to C active = false")
      (is (empty? inactive-false)
          "no inactive MS descendants reachable through active-only IS-A index"))))

(deftest ^{:live true :uk true} test-dialect-filter
  ;; Use clinical findings as a base: the UK edition ships concepts that have
  ;; en-GB dialect descriptions but no en-US descriptions, so `gb-only` is
  ;; known to be non-empty. This makes the dialect filter demonstrably
  ;; narrowing rather than trivially a subset. Verify at description level,
  ;; since dialect (language) refset members are keyed by description id.
  (when (and (contains? (hermes/installed-reference-sets *svc*) 999001261000000100)
             (contains? (hermes/installed-reference-sets *svc*) 900000000000508004))
    (let [base    "< 404684003"
          all     (hermes/ecl->concept-ids *svc* base)
          gb      (hermes/ecl->concept-ids *svc* (str base " {{ D dialectId = 999001261000000100 }}"))
          us      (hermes/ecl->concept-ids *svc* (str base " {{ D dialectId = 900000000000508004 }}"))
          gb-only (set/difference gb us)
          refset-members (fn [desc-id refset-id]
                           (hermes/component-refset-items *svc* desc-id refset-id))]
      (is (seq gb) "en-GB dialect should return results")
      (is (seq us) "en-US dialect should return results")
      (is (set/subset? gb all) "dialect-filtered set is a subset of unfiltered")
      (is (set/subset? us all) "dialect-filtered set is a subset of unfiltered")
      (is (seq gb-only)
          "UK edition should contain concepts with en-GB but no en-US descriptions")
      (doseq [cid (take 3 gb-only)]
        (let [desc-ids (map :id (hermes/descriptions *svc* cid))]
          (is (some #(seq (refset-members % 999001261000000100)) desc-ids)
              (str "gb-only concept " cid " should have at least one description in en-GB"))
          (is (not-any? #(seq (refset-members % 900000000000508004)) desc-ids)
              (str "gb-only concept " cid " should have no description in en-US")))))))

(deftest ^{:live true :uk true} test-dialect-filter-alias
  (testing "Official dialect alias syntax should match the corresponding dialectId filter"
    (when (contains? (hermes/installed-reference-sets *svc*) 999001261000000100)
      (let [by-alias (hermes/ecl->concept-ids *svc* "<< 24700007 {{ D dialect = en-gb }}")
            by-id    (hermes/ecl->concept-ids *svc* "<< 24700007 {{ D dialectId = 999001261000000100 }}")]
        (is (= by-alias by-id)
            "Dialect aliases should expand to the same filter as dialectId")))))

(deftest ^:live test-attributes
  (let [ecl-1 "<  19829001 |Disorder of lung| :   116676008 |Associated morphology|  = <<  79654002 |Edema|"
        ecl-2 "<  19829001 |Disorder of lung| :   116676008 |Associated morphology|  = (<< (* {{ D term = \"zzzzzzzzzzz\"}}))" ;; should be empty result
        result-1 (seq (hermes/expand-ecl *svc* ecl-1))
        result-2 (seq (hermes/expand-ecl *svc* ecl-2))]
    (is result-1 "Expected results")
    (is (not result-2) "If attribute value matches nothing, should return empty")))

(deftest ^:live test-same-group-matching
  (testing "Attribute groups enforce same-group semantics"
    ;; 392259005: amoxicillin 875mg / clavulanate 125mg oral product
    ;; Group 1: amoxicillin ingredient + strength 875
    ;; Group 2: clavulanate ingredient + strength 125
    ;; With correct same-group matching, {ingredient=amoxicillin, strength<250}
    ;; must NOT match because no single group has both amoxicillin AND strength <250.
    (let [in-same-group (hermes/ecl->concept-ids *svc*
                          "< 27658006: { << 127489000 = << 372687004, 1142135004 > #250 }")
          cross-group   (hermes/ecl->concept-ids *svc*
                          "< 27658006: { << 127489000 = << 372687004, 1142135004 < #250 }")]
      (is (in-same-group 392259005)
          "Group 1 has amoxicillin with strength 875 (>250) — should match")
      (is (not (cross-group 392259005))
          "No single group has amoxicillin AND strength <250 — cross-group must not match")))
  (testing "Grouped != uses raw relationship targets"
    (let [results (hermes/ecl->concept-ids *svc*
                    "< 27658006: { << 127489000 = << 372687004, << 127489000 != << 105590001 }")]
      (is (not (results 392259005))
          "Amoxicillin is a subtype of Substance, so grouped != << Substance must not match"))))

(deftest ^:live test-ungrouped-not-equals-existential
  (testing "Ungrouped != is existential: concept must have the attribute, with at least one value outside the target set"
    ;; 392259005 is amoxicillin 875mg / clavulanate 125mg — it has two
    ;; active ingredient values (amoxicillin and clavulanate). Ungrouped !=
    ;; should match because clavulanate is NOT a subtype of amoxicillin.
    (let [results (hermes/ecl->concept-ids *svc*
                    "< 27658006 |Amoxicillin-containing product| : << 127489000 |Has active ingredient| != << 372687004 |Amoxicillin|")]
      (is (seq results) "Ungrouped != should return products with non-amoxicillin ingredients")
      (is (results 392259005) "Amoxicillin/clavulanate product should match because clavulanate != amoxicillin")))
  (testing "Ungrouped != must not include concepts without the attribute"
    ;; 24700007 (MS) has no 'has active ingredient' attribute
    (let [results (hermes/ecl->concept-ids *svc*
                    "<< 24700007 |Multiple sclerosis| : << 127489000 |Has active ingredient| != << 372687004 |Amoxicillin|")]
      (is (empty? results)
          "MS has no ingredient attribute — must not match ungrouped !=")))
  (testing "Ungrouped != includes concepts with both matching and non-matching values"
    ;; 392259005 has both amoxicillin (in target set) and clavulanate (not in target set).
    ;; With existential semantics, it should appear in BOTH = and != result sets.
    (let [eq-results  (hermes/ecl->concept-ids *svc*
                        "< 27658006 : << 127489000 = << 372687004 |Amoxicillin|")
          neq-results (hermes/ecl->concept-ids *svc*
                        "< 27658006 : << 127489000 != << 372687004 |Amoxicillin|")
          overlap     (set/intersection eq-results neq-results)]
      (is (seq eq-results) "= should return amoxicillin products")
      (is (seq neq-results) "!= should return non-amoxicillin products")
      (is (overlap 392259005)
          "Combo product should appear in both = and != results (existential semantics)")
      ;; Also verify != is NOT the simple complement of =
      (let [all-products (hermes/ecl->concept-ids *svc* "< 27658006")]
        (is (not= neq-results (set/difference all-products eq-results))
            "!= must not be the simple complement of = (would wrongly include products without any ingredient)")))))

(deftest ^:live test-history-profiles
  (let [base  (hermes/ecl->concept-ids *svc* "<< 195967001 |Asthma|")
        h-min (hermes/ecl->concept-ids *svc* "<< 195967001 |Asthma| {{+HISTORY-MIN}}")
        h-mod (hermes/ecl->concept-ids *svc* "<< 195967001 |Asthma| {{+HISTORY-MOD}}")
        h-max (hermes/ecl->concept-ids *svc* "<< 195967001 |Asthma| {{+HISTORY-MAX}}")
        h-all (hermes/ecl->concept-ids *svc* "<< 195967001 |Asthma| {{+HISTORY}}")]
    (is (set/subset? base h-min) "HISTORY-MIN should include all active concepts")
    (is (set/subset? h-min h-mod) "HISTORY-MOD should include everything from HISTORY-MIN")
    (is (set/subset? h-mod h-max) "HISTORY-MAX should include everything from HISTORY-MOD")
    (is (set/subset? h-max h-all) "HISTORY should include everything from HISTORY-MAX")
    ;; Proper-subset assertions: without these, a no-op implementation that
    ;; returned `base` for every profile would pass the subset chain.
    (is (< (count base) (count h-min))
        "HISTORY-MIN should add inactive concepts beyond base")
    (is (< (count h-min) (count h-mod))
        "HISTORY-MOD should strictly extend HISTORY-MIN")
    (is (< (count h-mod) (count h-max))
        "HISTORY-MAX should strictly extend HISTORY-MOD")
    (is (= h-max h-all)
        "HISTORY (no profile suffix) should alias HISTORY-MAX in this implementation")))


(deftest ^:live test-chained-dot-notation
  (let [sites        (hermes/ecl->concept-ids *svc*
                       "< 19829001 |Disorder of lung| . 363698007 |Finding site|")
        site-parents (hermes/ecl->concept-ids *svc*
                       "< 19829001 |Disorder of lung| . 363698007 |Finding site| . 116680003 |Is a|")]
    (is (seq sites) "Single dot should return finding sites for lung disorders")
    (is (seq site-parents) "Chained dot should return IS-A parents of those finding sites")))

(deftest ^:live test-ungrouped-not-equals-with-conjunction
  (testing "!= AND = finds multi-ingredient products"
    (let [results (hermes/ecl->concept-ids *svc*
                    "< 27658006 : << 127489000 = << 372687004 AND << 127489000 != << 372687004")]
      (is (results 392259005)))))

(deftest ^:live test-base-query-narrowing-correctness
  (let [narrow (hermes/ecl->concept-ids *svc*
                 "< 27658006 : << 127489000 != << 372687004")
        broad  (hermes/ecl->concept-ids *svc*
                 "<< 27658006 : << 127489000 != << 372687004")]
    (is (set/subset? narrow broad))
    (is (not (narrow 27658006)))))

(deftest ^:live test-wildcard-equivalences
  (testing "<< *, >> *, <<! *, >>! * should all produce match-all queries"
    (is (search/q-match-all? (ecl/parse *svc* "<< *"))
        "<< * should produce MatchAllDocsQuery")
    (is (search/q-match-all? (ecl/parse *svc* ">> *"))
        ">> * should produce MatchAllDocsQuery")
    (is (search/q-match-all? (ecl/parse *svc* "<<! *"))
        "<<! * should produce MatchAllDocsQuery (every concept is child-or-self of some concept)")
    (is (search/q-match-all? (ecl/parse *svc* ">>! *"))
        ">>! * should produce MatchAllDocsQuery (every concept is parent-or-self of some concept)"))
  (testing "< * and <! * should produce the same query"
    (is (= (ecl/parse *svc* "< *") (ecl/parse *svc* "<! *"))
        "< * and <! * should be equivalent (all concepts except root)")))

(deftest ^:live test-wildcard-attribute-subset-invariant
  (testing "`* = V` must be a superset of `A = V` for any specific attribute A"
    (let [wild (hermes/ecl->concept-ids *svc* "< 19829001 : * = 79654002")
          spec (hermes/ecl->concept-ids *svc* "< 19829001 : 116676008 = 79654002")]
      (is (seq spec) "specific-attribute query must return results for the invariant to be meaningful")
      (is (set/subset? spec wild)
          "concepts matching a specific-attribute refinement must also match the wildcard"))))

(deftest ^:live test-wildcard-attribute-excludes-isa
  (testing "`* = V` must not treat IS-A as an attribute — §8.5 restricts `*` to descendants of 410662002 |Concept model attribute|"
    ;; IS-A (116680003) is NOT a descendant of ConceptModelAttribute. If it
    ;; were wrongly included, every direct IS-A child of V would appear in
    ;; `< V : * = V` via its `IS-A → V` relationship — producing a result
    ;; polluted with all direct children of V regardless of attribute semantics.
    (let [direct-children (hermes/ecl->concept-ids *svc* "<! 19829001 |Disorder of lung|")
          wild            (hermes/ecl->concept-ids *svc* "< 19829001 |Disorder of lung| : * = 19829001 |Disorder of lung|")]
      (is (seq direct-children) "precondition: 19829001 must have direct IS-A children")
      (is (not (set/subset? direct-children wild))
          "direct IS-A children of V must not uniformly appear in `< V : * = V` — IS-A is not an ECL attribute"))))

(deftest ^:live test-grouped-wildcard-attribute
  (testing "`{ * = V }` must be a superset of `{ A = V }` for any specific attribute A"
    (let [wild (hermes/ecl->concept-ids *svc* "<< 24700007 : { * = 79654002 }")
          spec (hermes/ecl->concept-ids *svc* "<< 24700007 : { 116676008 = 79654002 }")]
      (is (set/subset? spec wild)
          "specific-attribute group results must be a subset of wildcard group results"))))

(deftest ^:live test-wildcard-attribute-unsupported-combos
  (testing "`* = *` throws — matches essentially all concepts with any attribute"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 19829001 : * = *"))))
  (testing "`* != V` throws — would require iterating every concept"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 27658006 : * != << 372687004"))))
  (testing "grouped `{ * != V }` throws — would require iterating every concept"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 27658006 : { * != << 372687004 }"))))
  (testing "cardinality combined with wildcard attribute throws"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 19829001 : [1..*] * = 79654002"))))
  (testing "reverse flag combined with wildcard attribute throws"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 19829001 : R * = 79654002"))))
  (testing "numeric comparison with wildcard attribute throws"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 763158003 : * > #100")))
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 763158003 : * = #250")))))

(deftest ^:live test-attribute-umbrella-is-concept-model-attribute
  (testing "ECL §8.5: expression-operator attribute names must resolve to descendants of 410662002 |Concept model attribute|"
    ;; 367565008 |Intention - attribute| is a descendant of 246061005 |Attribute|
    ;; but NOT of 410662002 |Concept model attribute|. It must not be accepted as
    ;; a valid ECL refinement attribute name.
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "< 24700007 : 367565008 = 79654002")))))

(deftest ^:live test-bare-wildcard-term-requires-substrate
  (testing "`wild:\"*\"` without a narrowing outer expression is rejected — would iterate every description"
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "* {{ D term = wild:\"*\" }}")))
    (is (thrown? Exception (hermes/ecl->concept-ids *svc* "<< * {{ D term = wild:\"*\" }}"))))
  (testing "`wild:\"*\"` with a narrowing substrate does not iterate the full index and is accepted"
    ;; Every MS description matches `*` (so the filter itself is trivial); the
    ;; point is that the outer `24700007` narrows the description scan to
    ;; that concept's descriptions.
    (is (contains? (hermes/ecl->concept-ids *svc* "24700007 {{ D term = wild:\"*\" }}") 24700007))))

;; Hand-crafted properties modelling concept 392259005
;; (amoxicillin 875mg / clavulanate 125mg oral product)
;; Group 1: amoxicillin (372687004) with strength #875
;; Group 2: clavulanate (395938000) with strength #125
(def ^:private test-properties
  {0 {116680003 #{778315007}}
   1 {762949000 #{372687004}
      1142135004 #{"#875"}
      732945000 #{258684004}}
   2 {762949000 #{395938000}
      1142135004 #{"#125"}
      732945000 #{258684004}}})

(def ^:private mixed-expression-properties
  {1 {762949000 #{372687004 395938000}}})

(def ^:private mixed-concrete-properties
  {1 {1142135004 #{"#875" "#125"}}})

(def ^:private typed-concrete-properties
  {1 {762949000 #{"\"abc\""}
      1142135004 #{"true"}
      732945000 #{"false"}}})

(def ^:private group-zero-only-properties
  {0 {116680003 #{778315007}}})

(deftest ^:live test-satisfies-group-constraints
  (testing "Conjunction of expression and concrete constraints"
    (is (ecl/group-constraints-satisfied? test-properties
          [[:in #{762949000} #{372687004}]
           [:> #{1142135004} 250.0]])
        "Group 1 has amoxicillin AND strength 875 > 250")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:in #{762949000} #{372687004}]
                [:< #{1142135004} 250.0]]))
        "No single group has amoxicillin AND strength < 250")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:in #{762949000} #{395938000}]
           [:< #{1142135004} 250.0]])
        "Group 2 has clavulanate AND strength 125 < 250"))
  (testing "Numeric comparison operators"
    (is (ecl/group-constraints-satisfied? test-properties
          [[:= #{1142135004} 875.0]]))
    (is (ecl/group-constraints-satisfied? test-properties
          [[:>= #{1142135004} 875.0]]))
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:> #{1142135004} 875.0]]))
        "875 is not > 875")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:< #{1142135004} 875.0]])
        "Group 2 has 125 < 875")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:<= #{1142135004} 125.0]]))
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:< #{1142135004} 125.0]]))
        "125 is not < 125")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:!= #{1142135004} 875.0]])
        "Group 2 has 125 which != 875")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:!= #{1142135004} 999.0]])
        "Both groups have values (875, 125) that differ from 999")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:!= #{100000102} 999.0]]))
        "!= requires a value to exist that differs")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:= #{100000102} 999.0]]))
        "= requires a value to exist that matches"))
  (testing "Set membership operators"
    (is (ecl/group-constraints-satisfied? test-properties
          [[:in #{762949000} #{372687004 395938000}]])
        "Both groups have an ingredient in the set")
    (is (ecl/group-constraints-satisfied? test-properties
          [[:not-in #{762949000} #{372687004}]])
        "Group 2 has clavulanate which is not in #{amoxicillin}")
    (is (ecl/group-constraints-satisfied?
          mixed-expression-properties
          [[:not-in #{762949000} #{372687004}]])
        "!= is existential within a group: one non-matching value is enough even if a matching value also exists")
    (is (not (ecl/group-constraints-satisfied?
               mixed-expression-properties
               [[:not-in #{762949000} #{372687004 395938000}]]))
        "!= should fail when all values are within the exclusion set")
    (is (not (ecl/group-constraints-satisfied?
               mixed-expression-properties
               [[:not-in #{762949000} #{372687004 395938000 24700007}]]))
        "!= should fail when every actual value is excluded, even if the exclusion set has extras")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{1}}}
               [[:not-in #{762949000} #{372687004}]]))
        "!= must not match when the constrained attribute is absent from the group")
    (is (not (ecl/group-constraints-satisfied?
               {1 {762949000 #{372687004}}}
               [[:not-in #{762949000} #{372687004 387562000 105590001}]]))
        "!= must evaluate raw relationship targets, not ancestor-expanded values")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:not-in #{762949000} #{372687004 395938000}]]))
        "Both groups have ingredients in the exclusion set"))
  (testing "Minus operator"
    (is (ecl/group-constraints-satisfied? test-properties
          [[:minus #{762949000} #{372687004}]])
        "Group 2 has clavulanate which is outside the amoxicillin set")
    (is (ecl/group-constraints-satisfied?
          mixed-expression-properties
          [[:minus #{762949000} #{372687004}]])
        "MINUS is existential within a group: one value outside the exclusion set is enough")
    (is (not (ecl/group-constraints-satisfied?
               {1 {762949000 #{372687004}}}
               [[:minus #{762949000} #{372687004 387562000 105590001}]]))
        "MINUS must evaluate raw relationship targets, not ancestor-expanded values")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:minus #{762949000} #{372687004 395938000}]]))
        "All ingredient values are in the exclusion set")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{1}}}
               [[:minus #{762949000} #{372687004}]]))
        "MINUS must not match when the constrained attribute is absent from the group"))
  (testing "In operator"
    (is (ecl/group-constraints-satisfied?
          mixed-expression-properties
          [[:in #{762949000} #{372687004}]])
        "A mixed group should satisfy :in when any actual value is in the target set")
    (is (not (ecl/group-constraints-satisfied?
               mixed-expression-properties
               [[:in #{762949000} #{24700007}]]))
        ":in should fail when no actual values are in the target set")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{1}}}
               [[:in #{762949000} #{372687004}]]))
        ":in must not match when the constrained attribute is absent from the group"))
  (testing "Concrete equality and inequality with mixed values"
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:= #{1142135004} 875.0]])
        "Concrete equality should succeed when any value matches")
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:!= #{1142135004} 875.0]])
        "Concrete inequality should be existential within a group")
    (is (not (ecl/group-constraints-satisfied?
               {1 {1142135004 #{"#875"}}}
               [[:!= #{1142135004} 875.0]]))
        "Concrete inequality should fail when the only actual value equals the target")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{"#875"}}}
               [[:= #{1142135004} 875.0]]))
        "Concrete equality must not match when the constrained attribute is absent from the group"))
  (testing "Concrete ordering operators with mixed values"
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:> #{1142135004} 500.0]])
        "A mixed numeric group should satisfy > when any value matches")
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:< #{1142135004} 500.0]])
        "A mixed numeric group should satisfy < when any value matches")
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:>= #{1142135004} 875.0]])
        "A mixed numeric group should satisfy >= at the equality boundary")
    (is (ecl/group-constraints-satisfied?
          mixed-concrete-properties
          [[:<= #{1142135004} 125.0]])
        "A mixed numeric group should satisfy <= at the equality boundary")
    (is (not (ecl/group-constraints-satisfied?
               mixed-concrete-properties
               [[:> #{1142135004} 875.0]]))
        "Strict > should remain false at the equality boundary")
    (is (not (ecl/group-constraints-satisfied?
               mixed-concrete-properties
               [[:< #{1142135004} 125.0]]))
        "Strict < should remain false at the equality boundary")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{"#875"}}}
               [[:> #{1142135004} 500.0]]))
        "Ordering operators must not match when the constrained attribute is absent from the group"))
  (testing "String and boolean concrete values"
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:= #{762949000} "abc"]])
        "String equality should match the unwrapped string value")
    (is (not (ecl/group-constraints-satisfied?
               typed-concrete-properties
               [[:!= #{762949000} "abc"]]))
        "String inequality should fail when the only value equals the target")
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:!= #{762949000} "def"]])
        "String inequality should succeed when the stored value differs")
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:= #{1142135004} true]])
        "Boolean true equality should match")
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:= #{732945000} false]])
        "Boolean false equality should match")
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:!= #{1142135004} false]])
        "Boolean inequality should succeed when the stored value differs")
    (is (ecl/group-constraints-satisfied?
          typed-concrete-properties
          [[:!= #{732945000} true]])
        "Boolean inequality should succeed for false != true")
    (is (not (ecl/group-constraints-satisfied?
               {1 {999 #{"\"abc\""}}}
               [[:= #{762949000} "abc"]]))
        "Typed concrete equality must not match when the constrained attribute is absent from the group"))
  (testing "Wildcard operator"
    (is (ecl/group-constraints-satisfied? test-properties
          [[:* #{762949000} nil]])
        "Groups have ingredient values")
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:* #{100000102} nil]]))
        "No group has values for non-existent type"))
  (testing "Group 0 never matches"
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:in #{116680003} #{778315007}]]))
        "IS-A in group 0 should not match"))
  (testing "Empty constraints match any non-zero group"
    (is (ecl/group-constraints-satisfied? test-properties [])
        "Vacuous truth: no constraints to fail")
    (is (not (ecl/group-constraints-satisfied? group-zero-only-properties []))
        "Empty constraints should still require a non-zero group to exist"))
  (testing "Conjunction requires one group to satisfy all constraints"
    (is (not (ecl/group-constraints-satisfied? test-properties
               [[:in #{762949000} #{372687004}]
                [:= #{1142135004} 125.0]]))
        "Constraints split across groups must not be combined"))
  (testing "Numeric ordering on non-numeric concrete values throws"
    (is (thrown? ClassCastException
          (ecl/group-constraints-satisfied?
            typed-concrete-properties
            [[:> #{762949000} 1.0]]))
        "Ordering comparisons should currently throw on non-numeric concrete values"))
  (testing "Wildcard attribute is restricted to Concept model attribute descendants (ECL §8.5)"
    ;; With a 3rd arg of allowed CMA attribute types, `:wildcard` must consider
    ;; only type-ids in that set. Without it, a non-CMA type (e.g. from a
    ;; malformed extension) would spuriously satisfy `* = V`.
    (let [cma-types #{116676008 363698007}]
      (is (not (ecl/group-constraints-satisfied?
                 {1 {367565008 #{79654002}}}
                 cma-types
                 [[:in :wildcard #{79654002}]]))
          "Type 367565008 (not a CMA descendant) must not satisfy `* = V`")
      (is (ecl/group-constraints-satisfied?
            {1 {116676008 #{79654002}}}
            cma-types
            [[:in :wildcard #{79654002}]])
          "Type 116676008 (a CMA descendant) should satisfy `* = V`")
      (is (not (ecl/group-constraints-satisfied?
                 {1 {367565008 #{79654002}
                     116676008 #{99999999}}}
                 cma-types
                 [[:in :wildcard #{79654002}]]))
          "Value 79654002 under a non-CMA type must not be counted toward `* = 79654002`"))))

(deftest ^:live test-satisfies-ungrouped-constraint
  (testing "Ungrouped :not-in merges all groups"
    (is (ecl/ungrouped-constraint-satisfied? test-properties
          [:not-in #{762949000} #{372687004}])
        "Clavulanate in group 2 is not in #{amoxicillin}")
    (is (not (ecl/ungrouped-constraint-satisfied? test-properties
               [:not-in #{762949000} #{372687004 395938000}]))
        "Both ingredients are in the exclusion set"))
  (testing "Ungrouped :in spans all groups"
    (is (ecl/ungrouped-constraint-satisfied? test-properties
          [:in #{762949000} #{372687004}])
        "Amoxicillin is in group 1")
    (is (ecl/ungrouped-constraint-satisfied? test-properties
          [:in #{116680003} #{778315007}])
        "IS-A in group 0 should be visible to ungrouped evaluation"))
  (testing "Ungrouped constraint requires attribute presence"
    (is (not (ecl/ungrouped-constraint-satisfied? test-properties
               [:not-in #{100000102} #{372687004}]))
        "!= must not match when the attribute is absent")
    (is (not (ecl/ungrouped-constraint-satisfied? test-properties
               [:in #{100000102} #{372687004}]))
        "= must not match when the attribute is absent"))
  (testing "Ungrouped :* checks attribute presence across all groups"
    (is (ecl/ungrouped-constraint-satisfied? test-properties
          [:* #{116680003} nil])
        "IS-A exists in group 0")
    (is (not (ecl/ungrouped-constraint-satisfied? test-properties
               [:* #{100000102} nil]))
        "Non-existent type should not match"))
  (testing "Concept with no properties"
    (is (not (ecl/ungrouped-constraint-satisfied? {}
               [:not-in #{762949000} #{372687004}]))
        "Empty properties should not satisfy any constraint")))

(deftest ^:live test-any-group-satisfies
  (let [store (.-store *svc*)]
    ;; Constraints use fully realised target sets, while store data is checked
    ;; against raw grouped relationship targets.
    (testing "Amoxicillin + strength > 250"
      (is (ecl/concept-satisfies-group-constraints? store 392259005
            [[:in #{762949000} #{372687004}]
             [:> #{1142135004} 250.0]])))
    (testing "Amoxicillin + strength < 250 — no single group"
      (is (not (ecl/concept-satisfies-group-constraints? store 392259005
                 [[:in #{762949000} #{372687004}]
                  [:< #{1142135004} 250.0]]))))
    (testing "Clavulanate + strength < 250"
      (is (ecl/concept-satisfies-group-constraints? store 392259005
            [[:in #{762949000} #{395938000}]
             [:< #{1142135004} 250.0]])))
    (testing "A substance ingredient must not satisfy != << Substance"
      (is (not (ecl/concept-satisfies-group-constraints? store 392259005
                 [[:in #{762949000} #{372687004}]
                  [:not-in #{762949000} (store/all-children store 105590001)]]))))))

(comment
  (def ^:dynamic *svc* (hermes/open "snomed.db"))
  (ecl/parse *svc* " ^  447562003 |ICD-10 complex map reference set|  {{ M mapTarget = \"J45.9\" }}")
  (run-tests))
