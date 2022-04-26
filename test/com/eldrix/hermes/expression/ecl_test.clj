(ns com.eldrix.hermes.expression.ecl-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [clojure.spec.test.alpha :as stest]))

(stest/instrument)

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (f)
    (hermes/close *svc*)))

(use-fixtures :once live-test-fixture)

(def ^:live simple-tests
  [{:ecl "404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{404684003})))}
   {:ecl "<  24700007"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 24700007)) "descendant of should not include concept itself")
           (is (contains? concept-ids 426373005) "relapsing remitting MS (426373005) should be a type of MS (24700007)"))}
   {:ecl "<<  73211009 |Diabetes mellitus|"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 73211009) "descendent-or-self-of should include concept itself")
           (is (contains? concept-ids 46635009) "type 1 diabetes mellitus (46635009) should be a type of diabetes mellitus (73211009)"))}
   {:ecl "<!  404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 404684003)) "'child of' should not include concept itself")
           (is (contains? concept-ids 64572001) "'child of' clinical finding should include 'disease (64572001)")
           (is (not (contains? concept-ids 24700007)) "'child' of' should include only proximal relationships"))}
   {:ecl "    >  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 40541001)) "'ancestor of' should not include concept itself")
           (is (contains? concept-ids 19829001) "ancestors of acute pulmonary oedema should include 'disorder of lung' (19829001)"))}
   {:ecl "    >!  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 40541001)) "'parent of' should not include concept itself")
           (is (contains? concept-ids 19242006) "pulmonary oedema should be a proximal parent of acute pulmonary oedema")
           (is (not (contains? concept-ids 19829001)) "(proximal) parent of acute pulmonary oedema should not include 'disorder of lung' (19829001)"))}
   {:ecl "24700007 |Multiple sclerosis| AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (empty? concept-ids) "multiple sclerosis should not be in lateralizable body structure refset"))}
   {:ecl "(24136001 |Hip joint| OR 53120007|Arm|) AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{24136001 53120007}) "both 'hip joint' and 'arm' should be in lateralizable body structure refset"))}
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  =  79654002 |Edema|"
    :f   (fn [concept-ids]
           (let [morphologies (->> concept-ids
                                   (map #(hermes/get-parent-relationships-of-type *svc* % snomed/AssociatedMorphology))
                                   (map set))]
             (is (every? true? (map #(contains? % 79654002) morphologies))) ;; all must have EXACTLY oedema as morphology
             (is (every? false? (map #(contains? % 85628007) morphologies)))))} ;; and *not* a subtype of oedema such as chronic oedema
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  = <<  79654002 |Edema|"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 40541001)))}          ;; acute pulmonary oedema has morphology 'acute oedema' and should be included via this expression
   {:ecl "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|"
    :f2  (fn [ec]
           (is (store/is-a? nil ec 404684003))              ;; are all a clinical finding?
           (is (store/has-property? nil ec 363698007 39057004)) ;; are all affecting the pulmonary value?
           (is (store/has-property? nil ec 116676008 415582006)))} ;; are all a stenosis?
   {:ecl " * :  246075003 |Causative agent|  =  387517004 |Paracetamol|"
    :f2  (fn [ec] (is (store/has-property? nil ec 246075003 387517004)))}

   ;; attribute groups
   {:ecl "<  404684003 |Clinical finding| :
           {  363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| ,
              116676008 |Associated morphology|  = <<  415582006 |Stenosis| },
           {  363698007 |Finding site|  = <<  53085002 |Right ventricular structure| ,
              116676008 |Associated morphology|  = <<  56246009 |Hypertrophy| }"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 86299006)))}          ;; this should find tetralogy of Fallot

   ;; attribute constraint operators
   {:ecl "  <<  404684003 |Clinical finding| :\n        <<  47429007 |Associated with|  = <<  267038008 |Edema|"}
   {:ecl "<<  404684003 |Clinical finding| :\n        >>  246075003 |Causative agent|  = <<  267038008 |Edema|"}

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
   {:ecl "   <  404684003 |Clinical finding| :\n          116676008 |Associated morphology|  != <<  26036001 |Obstruction|"}])


  ;; {:ecl ""}
  ;; {:ecl ""}
  ;; {:ecl ""}
  ;; {:ecl ""}



(def not-yet-implemented
  ;; need to implement cardinality - see https://confluence.ihtsdotools.org/display/DOCECL/6.5+Exclusion+and+Not+Equals
  {:ecl " <  404684003 |Clinical finding| :\n         [0..0]  116676008 |Associated morphology|  != <<  26036001 |Obstruction|"})


(deftest ^:live test-equivalence
  (let [r1 (hermes/expand-ecl *svc* " < ( 125605004 |Fracture of bone| . 363698007 |Finding site| )")
        r2 (hermes/expand-ecl *svc* "<  272673000 |Bone structure|")]
    (is (= r1 r2))))

(deftest ^:live do-simple-tests
  (doseq [{:keys [ecl f f2] } simple-tests]
    (let [results (hermes/expand-ecl *svc* ecl)]
      (when f (f (set (map :conceptId results))))
      (when f2 (dorun (->> results
                           (map #(hermes/get-extended-concept *svc* (:conceptId %)))
                           (map f2)))))))

(deftest ^:live test-history
  (let [r1 (hermes/expand-ecl *svc* "<<  195967001 |Asthma|")
        r2 (hermes/expand-ecl-historic *svc* "<<  195967001 |Asthma|")
        r3 (hermes/expand-ecl *svc* "<< 195967001 {{+HISTORY}}")]
    (is (< (count r1) (count r2)))
    (is (= (set r2) (set r3)))))

(deftest ^:live test-member-filter
  (dorun (->> (hermes/expand-ecl *svc* " ^  447562003 |ICD-10 complex map reference set|  {{ M mapPriority = #1, mapTarget = \"J45.9\" }}")
              (map :conceptId)
              (map #(hermes/get-component-refset-items *svc* % 447562003))
              (map #(map :mapTarget %))
              (map #(some (fn [s] (.startsWith "J45.9" s)) %))
              (map #(is true? %)))))


(comment
  (run-tests))