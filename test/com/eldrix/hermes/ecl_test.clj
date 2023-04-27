(ns com.eldrix.hermes.ecl-test
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is use-fixtures run-tests testing]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.ecl :as ecl]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]))

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
                                   (map #(hermes/parent-relationships-of-type *svc* % snomed/AssociatedMorphology))
                                   (map set))]
             (is (every? true? (map #(contains? % 79654002) morphologies))) ;; all must have EXACTLY oedema as morphology
             (is (every? false? (map #(contains? % 85628007) morphologies)))))} ;; and *not* a subtype of oedema such as chronic oedema
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  = <<  79654002 |Edema|"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 40541001)))}          ;; acute pulmonary oedema has morphology 'acute oedema' and should be included via this expression
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
           (is (contains? concept-ids 86299006)))}          ;; this should find tetralogy of Fallot

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
  (doseq [{:keys [ecl as f] :or {as :concept-ids}} simple-tests]
    (let [results (hermes/expand-ecl *svc* ecl)]
      (when f
        (case as
          :concept-ids (f (set (map :conceptId results)))
          :extended-concept (run! f (map #(hermes/extended-concept *svc* (:conceptId %)) results)))))))

(deftest ^:live test-history
  (let [r1 (set (hermes/expand-ecl *svc* "<<  195967001 |Asthma|"))
        r2 (set (hermes/expand-ecl-historic *svc* "<<  195967001 |Asthma|"))
        r3 (set (hermes/expand-ecl *svc* "<< 195967001 {{+HISTORY}}"))]
    (is (< (count r1) (count r2)))
    (is (= r2 r3))
    (is (set/subset? r1 r2))))


(def member-filter-tests
  [{:ecl  " ^  447562003 |ICD-10 complex map reference set|  {{ M mapTarget = \"J45.9\" }}"
    :incl #{195967001 707447008 401193004}}])

(deftest ^:live test-member-filter
  (dorun (->> (hermes/expand-ecl *svc* " ^  447562003 |ICD-10 complex map reference set|  {{ M mapPriority = #1, mapTarget = \"J45.9\" }}")
              (map :conceptId)
              (map #(hermes/component-refset-items *svc* % 447562003))
              (map #(map :mapTarget %))
              (map #(some (fn [s] (.startsWith "J45.9" s)) %))
              (map #(is true? %))))
  (doseq [{:keys [ecl incl]} member-filter-tests]
    (let [results (hermes/expand-ecl *svc* ecl)]
      (when incl (is (set/subset? incl (set (map :conceptId results))))))))

(deftest ^:live test-refinement-with-wildcard-value
  (let [ch (a/chan)]
    (a/go (a/>!! ch (hermes/expand-ecl *svc* "<24700007: 370135005 =*")))
    (let [[v c] (a/alts!! [ch (a/timeout 200)])]
      (is (= c ch) "Timeout during expansion of ECL containing a refinement with a wildcard value")
      (let [results (->> v (map :conceptId) distinct
                         (map #(boolean (seq (hermes/parent-relationships-of-type *svc* % 370135005)))))]
        (is (seq results) "Invalid results")
        (is (every? true? results) "Invalid results")))))

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
  (let [r1 (set (hermes/expand-ecl *svc* "<  64572001 |Disease|  {{ term = \"heart att\"}}"))
        r2 (set (hermes/search *svc* {:s "heart att" :constraint "<  64572001 |Disease| "}))]
    (is (= r1 r2))))

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
        r2 (hermes/expand-ecl *svc* "*:*=#250000")]
    (is (pos? (count r1)) "No results found for ECL expression containing concrete values")
    (is (pos? (count r2)) "No results found for ECL expression containing concrete values")
    (is (every? true? (map #(some-concrete-value? (:conceptId %) 1142135004 "#250") r1)))
    (is (every? true? (map #(some-concrete-value? (:conceptId %) "#250000") r2)))))

(deftest ^:live test-query-for-refset-field
  (let [ecl " ^ [targetComponentId]  900000000000527005 |SAME AS association reference set|  {{ M referencedComponentId =  67415000 |Hay asthma|  }}"]
    (is (thrown? Exception (ecl/parse *svc* ecl)))))

(comment
  (def ^:dynamic *svc* (hermes/open "snomed.db"))
  (require '[com.eldrix.hermes.impl.ecl :as ecl])
  (ecl/parse *svc* " ^  447562003 |ICD-10 complex map reference set|  {{ M mapTarget = \"J45.9\" }}")
  (run-tests))